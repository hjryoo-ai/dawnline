package com.dawnline.fulfillment.application;

import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.common.error.ValidationException;
import com.dawnline.fulfillment.application.port.in.CloseWaveUseCase;
import com.dawnline.fulfillment.application.port.in.WaveView;
import com.dawnline.fulfillment.application.port.out.WaveRepository;
import com.dawnline.fulfillment.domain.FulfillmentErrorCode;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.fulfillment.domain.WaveCloseCause;
import com.dawnline.observability.MdcScope;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영자 조기 마감 (§5.2, ADR-054).
 *
 * <p>본문은 스케줄러와 같은 {@link WaveClosing} 이고 원인만 {@link WaveCloseCause#MANUAL} 이다. Redis 락은 잡지
 * 않는다 — 사람이 누른 요청을 「다른 인스턴스가 처리 중」으로 돌려보낼 이유가 없고, 겹치면 먼저 잡은 쪽이
 * 닫고 뒤는 409 를 받는다(결정 5).
 *
 * <p><strong>차례를 건너뛰지 않는다</strong>(ADR-054 후속, 2026-09-25). 닫으려는 웨이브가 열려 있고 같은 캠프 · 티어에
 * 더 이른 컷오프의 열린 웨이브가 있으면 409 {@code not-next-wave} 로 아무것도 하지 않는다. 판정은 대상이 열려 있을
 * 때만 한다 — 이미 닫힌 웨이브를 다시 누른 사람은 여전히 {@code wave-not-open} 과 {@code closeCause} 를 받아야
 * 한다(감사 {@code UNKNOWN} 해소의 근거, RB-07). 스케줄러는 컷오프 순으로 닫으므로 이 판정이 없다.
 *
 * <p>{@code reason} 은 저장하지 않고 마감 로그 한 줄에 싣는다. ops-api 가 보낸 {@code auditId} 가 같은 줄의
 * MDC 에 있으므로(§9.3) 감사 {@code UNKNOWN} 을 닫는 사람이 코어 로그 한 줄에서 id 와 이유를 함께 본다.
 */
public class CloseWaveService implements CloseWaveUseCase {

    private static final Logger log = LoggerFactory.getLogger(CloseWaveService.class);

    private final WaveClosing closing;
    private final WaveRepository waves;
    private final TransactionTemplate transactions;
    private final FulfillmentMetrics metrics;

    /**
     * @param closing            마감 본문 (스케줄러와 공유)
     * @param waves              차례 판정 — 더 이른 열린 웨이브가 있는가
     * @param transactionManager 요청마다 트랜잭션을 연다
     * @param metrics            §9.1 의 웨이브 편입량 게이지
     */
    public CloseWaveService(WaveClosing closing, WaveRepository waves, PlatformTransactionManager transactionManager,
            FulfillmentMetrics metrics) {
        this.closing = Objects.requireNonNull(closing, "closing");
        this.waves = Objects.requireNonNull(waves, "waves");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "tx"));
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public WaveView close(UUID waveId, String reason) {
        Objects.requireNonNull(waveId, "waveId");
        // 웹 어댑터의 @NotBlank 와 같은 문장이다. 유스케이스가 스스로 말하지 않으면 다른 입구(테스트·다음
        // 어댑터)가 이유 없는 마감을 만들 수 있다 — 이 커맨드에서 이유는 선택이 아니다(ADR-054 결정 2).
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw new ValidationException("reason 은 1~" + MAX_REASON_LENGTH + "자여야 합니다",
                    Map.of("field", "reason"));
        }
        return MdcScope.builder().waveId(waveId).call(() -> closeInScope(waveId, reason));
    }

    private WaveView closeInScope(UUID waveId, String reason) {
        WaveClosing.Outcome outcome = transactions.execute(status -> {
            // 트랜잭션 안에서 던지면 롤백된다 — 이 판정 전에는 아무것도 쓰지 않았다.
            waves.findById(waveId).filter(wave -> wave.status().acceptsOrders()).ifPresent(wave ->
                    waves.findEarliestOpenBefore(wave.campId(), wave.serviceTier(), wave.cutoffAt())
                            .ifPresent(earlier -> {
                                throw notNext(wave, earlier);
                            }));
            return closing.closeIfOpen(waveId, WaveCloseCause.MANUAL);
        });
        return switch (Objects.requireNonNull(outcome, "outcome")) {
            case WaveClosing.Outcome.NotFound _ -> throw NotFoundException.of("Wave", waveId);
            case WaveClosing.Outcome.NotOpen notOpen -> throw notOpen(notOpen.wave());
            case WaveClosing.Outcome.Closed closed -> {
                // 커밋 뒤다 — transactions.execute 가 돌아왔다.
                Wave wave = closed.wave();
                metrics.waveClosed(closed.campCode(), wave.serviceTier(), wave.orderCount());
                log.info("웨이브를 수동 마감했습니다. cutoffAt={} orderCount={} reason={}",
                        wave.cutoffAt(), wave.orderCount(), reason);
                yield WaveView.of(wave);
            }
        };
    }

    /**
     * 409 {@code not-next-wave} 의 본문 — 먼저 닫아야 했던 웨이브.
     */
    private static DomainException notNext(Wave wave, Wave earlier) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("earlierWaveId", earlier.id().toString());
        details.put("earlierCutoffAt", earlier.cutoffAt().toString());
        return new DomainException(FulfillmentErrorCode.NOT_NEXT_WAVE,
                "같은 캠프 · 티어에 더 이른 열린 웨이브가 있습니다 — 컷오프 " + earlier.cutoffAt() + " 의 " + earlier.id()
                        + " 가 먼저입니다: " + wave.id(), details);
    }

    /**
     * 409 의 본문 — 다시 누른 사람이 읽을 사실 셋.
     *
     * <p>{@code closeCause} 가 {@code MANUAL} 이면 앞의 요청(또는 다른 운영자)이 닫았고, {@code SCHEDULED} 면
     * 스케줄러가 먼저 닫았다. 이 구분이 감사 {@code UNKNOWN} 을 닫는 근거다(ADR-054 결정 6).
     */
    private static DomainException notOpen(Wave wave) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("currentState", wave.status().name());
        if (wave.closeCause() != null) {
            details.put("closeCause", wave.closeCause().name());
        }
        if (wave.closedAt() != null) {
            details.put("closedAt", wave.closedAt().toString());
        }
        return new DomainException(FulfillmentErrorCode.WAVE_NOT_OPEN,
                "웨이브가 이미 " + wave.status() + " 입니다: " + wave.id(), details);
    }
}
