package com.dawnline.tracking.application;

import com.dawnline.tracking.application.port.out.EventPartitions;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * {@code shipment_events} 일 파티션 생성·삭제 (DESIGN.md §5.4, §7.1 보존 30일).
 *
 * <h2>이 클래스가 하는 일은 날짜 계산뿐이다</h2>
 * 파티션 이름과 경계는 마이그레이션의 함수 둘이 정한다({@code tracking_ensure_event_partitions} ·
 * {@code tracking_drop_event_partitions}). 여기서는 <strong>주입된 시계</strong>로 오늘을 읽어
 * 「어제부터 오늘+N 까지」와 「오늘−보존일 이전」을 넘긴다(불변규칙 12).
 *
 * <h2>어제를 포함하는 이유</h2>
 * {@code occurred_at} 은 기사 단말의 사건 시각이다. 자정 직후에 도착하는 스캔은 어제 날짜를 달고
 * 오고, 그 파티션이 이미 지워졌거나 아직 없으면 INSERT 가 그 자리에서 실패한다. 하루를 뒤로
 * 여는 비용은 빈 테이블 하나다.
 *
 * <h2>실패는 조용하지 않다</h2>
 * 생성이 멈추면 며칠 뒤 스캔 API 가 통째로 실패한다 — 그때 원인을 찾는 것이 아니라 그 전에
 * 알아야 한다. {@link #partitionsAhead()} 가 {@code dawnline_shipment_partitions_ahead} 게이지로
 * 나가고(§9.1), 값은 <em>마지막 파티션 날짜 − 오늘</em>이라 스케줄러가 멈추면 날마다 줄어든다.
 * 멈춘 게이지는 건강해 보이기 때문에 「마지막으로 만든 수」가 아니라 「앞으로 남은 날」을 잰다.
 * 알림은 2 에서 걸린다(§9.4).
 */
public class ShipmentEventPartitions {

    private static final Logger log = LoggerFactory.getLogger(ShipmentEventPartitions.class);

    /** 늦게 도착하는 스캔을 받는 하루. */
    private static final int LOOKBACK_DAYS = 1;

    private final EventPartitions partitions;
    private final Clock clock;
    private final int aheadDays;
    private final int retentionDays;

    /**
     * 마지막으로 확인한 최대 파티션 날짜. 게이지가 매 스크레이프마다 카탈로그를 읽지 않게 한다.
     * 스케줄러가 멈춰도 게이지 값이 <em>오늘</em>에 따라 줄어들므로 캐시가 거짓을 만들지 않는다.
     */
    private final AtomicReference<@Nullable LocalDate> coveredThrough = new AtomicReference<>();

    /**
     * @param partitions    파티션 포트
     * @param clock         오늘을 읽는 시계 (불변규칙 12)
     * @param aheadDays     오늘로부터 앞으로 덮어 둘 일수. 기본 7
     * @param retentionDays 보존 일수 (§5.4 기본 30). 이 값보다 오래된 파티션을 지운다
     */
    public ShipmentEventPartitions(EventPartitions partitions, Clock clock, int aheadDays, int retentionDays) {
        this.partitions = Objects.requireNonNull(partitions, "partitions");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (aheadDays < 1) {
            throw new IllegalArgumentException("aheadDays 는 1 이상이어야 합니다: " + aheadDays);
        }
        if (retentionDays < 1) {
            throw new IllegalArgumentException("retentionDays 는 1 이상이어야 합니다: " + retentionDays);
        }
        if (retentionDays <= aheadDays) {
            // 보존이 선행 생성보다 짧으면 방금 만든 파티션을 같은 실행이 지운다.
            throw new IllegalArgumentException(
                    "retentionDays(%d)는 aheadDays(%d)보다 커야 합니다 — 만든 파티션을 같은 실행이 지웁니다"
                            .formatted(retentionDays, aheadDays));
        }
        this.aheadDays = aheadDays;
        this.retentionDays = retentionDays;
    }

    /**
     * 주기 실행 (기본 1시간). 기동 직후에도 한 번 돈다 — 마이그레이션이 만든 창이 재기동
     * 시점에는 이미 지났을 수 있다.
     *
     * <p>예외를 삼킨다. 다음 실행이 이어받으면 되고, 이어받지 못하면 게이지가 줄어 알림이 본다.
     */
    @Scheduled(
            fixedDelayString = "${dawnline.tracking.partitions.interval-ms:3600000}",
            initialDelayString = "${dawnline.tracking.partitions.initial-delay-ms:0}")
    public void maintain() {
        try {
            rotate();
        } catch (RuntimeException e) {
            log.error("shipment_events 파티션 관리 실패. dawnline_shipment_partitions_ahead 가 줄어든다.", e);
        }
    }

    /**
     * 만들고 지운다. 스케줄과 무관하게 직접 호출할 수 있다(테스트·운영 수동 실행).
     *
     * @return 이번 실행의 결과
     */
    public Rotated rotate() {
        LocalDate today = today();
        LocalDate from = today.minusDays(LOOKBACK_DAYS);
        int created = partitions.ensure(from, LOOKBACK_DAYS + 1 + aheadDays);
        int dropped = partitions.dropBefore(today.minusDays(retentionDays));
        LocalDate covered = partitions.lastPartitionDay();
        coveredThrough.set(covered);
        if (created > 0 || dropped > 0) {
            log.info("shipment_events 파티션 {}개 생성 · {}개 삭제 (덮인 마지막 날 {}, 보존 {}일)",
                    created, dropped, covered, retentionDays);
        }
        return new Rotated(created, dropped, covered);
    }

    /**
     * {@code dawnline_shipment_partitions_ahead} 게이지 값 — 오늘을 포함해 앞으로 덮여 있는 날 수.
     *
     * <p>0 이면 오늘 도착하는 스캔이 이미 실패하고 있다는 뜻이다.
     *
     * @return 0 이상
     */
    public int partitionsAhead() {
        LocalDate covered = coveredThrough.get();
        if (covered == null) {
            // 첫 실행 전이다. 카탈로그를 직접 본다 — 아직 아무것도 모르는 것을 0 으로 보고하면
            // 기동 직후마다 알림이 울린다.
            covered = partitions.lastPartitionDay();
            if (covered == null) {
                return 0;
            }
            coveredThrough.set(covered);
        }
        LocalDate today = today();
        if (covered.isBefore(today)) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(today, covered) + 1;
    }

    private LocalDate today() {
        // 파티션 경계가 UTC 자정이므로 오늘도 UTC 로 읽는다. 시계의 존에 기대면 같은 코드가
        // 환경마다 다른 날을 만든다.
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /**
     * 한 번의 실행 결과.
     *
     * @param created 만든 파티션 수
     * @param dropped 지운 파티션 수
     * @param coveredThrough 덮인 마지막 날. 파티션이 하나도 없으면 {@code null}
     */
    public record Rotated(int created, int dropped, @Nullable LocalDate coveredThrough) {
    }
}
