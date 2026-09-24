package com.dawnline.fulfillment.application;

import com.dawnline.fulfillment.application.port.out.FulfillmentEvents;
import com.dawnline.fulfillment.application.port.out.FulfillmentOrderRepository;
import com.dawnline.fulfillment.application.port.out.ReferenceData;
import com.dawnline.fulfillment.application.port.out.WaveRepository;
import com.dawnline.fulfillment.domain.Camp;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.fulfillment.domain.WaveCloseCause;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 웨이브 하나를 닫는 본문 — 스케줄러와 운영자가 <strong>같은 코드</strong>를 부른다 (§5.2, ADR-054 결정 5).
 *
 * <p>두 벌로 두면 「운영자가 닫으면 되는데 자동은 안 된다」 같은 차이가 생긴다. 호출자가 다른 것은 원인
 * ({@link WaveCloseCause}) 하나뿐이다.
 *
 * <p><strong>호출자의 트랜잭션 안에서</strong> 부른다. 트랜잭션을 여기서 열지 않는 이유는 경계를 정하는 것이
 * 호출자이기 때문이다 — 스케줄러는 웨이브마다, 운영자 경로는 요청마다 연다. 카운터·게이지도 여기서 올리지
 * 않는다: 커밋 뒤에 호출자가 {@link Outcome.Closed} 를 보고 올린다(CLAUDE.md 「카운터는 커밋 뒤에 센다」).
 *
 * <p>Redis 락은 이 본문의 일이 아니다. 중복 마감을 막는 것은 {@code FOR UPDATE} 와 상태 전이이고, 락은
 * 여러 인스턴스가 같은 일을 동시에 <em>시작하는</em> 낭비를 줄이는 스케줄러의 장치다.
 */
public class WaveClosing {

    private final WaveRepository waves;
    private final FulfillmentOrderRepository orders;
    private final FulfillmentEvents events;
    private final ReferenceData referenceData;
    private final Clock clock;

    /**
     * @param waves         웨이브 저장소
     * @param orders        주문 저장소 (마감 시 집계, ADR-025)
     * @param events        outbox 발행
     * @param referenceData 캠프 좌표·코드 조회
     * @param clock         시각 출처 (불변규칙 12)
     */
    public WaveClosing(WaveRepository waves, FulfillmentOrderRepository orders, FulfillmentEvents events,
            ReferenceData referenceData, Clock clock) {
        this.waves = Objects.requireNonNull(waves, "waves");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.events = Objects.requireNonNull(events, "events");
        this.referenceData = Objects.requireNonNull(referenceData, "referenceData");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 열려 있으면 닫는다.
     *
     * @param waveId 웨이브
     * @param cause  누가 닫는가
     * @return 닫았는가, 이미 닫혀 있었는가, 없는가
     * @throws IllegalStateException 캠프를 찾지 못했을 때 — 좌표 없는 {@code wave.closed} 는 하류가 계획할 수
     *                               없는 이벤트이므로 이 웨이브의 트랜잭션을 실패시킨다
     */
    public Outcome closeIfOpen(UUID waveId, WaveCloseCause cause) {
        Objects.requireNonNull(waveId, "waveId");
        Objects.requireNonNull(cause, "cause");

        // 진행 중인 편입(공유 락)이 전부 커밋될 때까지 기다린 뒤 배타로 잡는다 (ADR-025).
        Optional<Wave> locked = waves.findByIdForUpdate(waveId);
        if (locked.isEmpty()) {
            return new Outcome.NotFound();
        }
        Wave wave = locked.get();
        if (!wave.status().acceptsOrders()) {
            // 이미 다른 쪽이 닫았다. 스케줄러에게는 세 번째 방어이고, 운영자에게는 409 의 근거다.
            return new Outcome.NotOpen(wave);
        }
        wave.beginClosing();
        // 카운트는 여기서 한 번 센다 (ADR-025). 배타 락을 들고 있으므로 새 편입이 없다.
        int orderCount = orders.countPlannedInWave(wave.id());
        wave.close(clock.instant(), orderCount, cause);
        waves.update(wave);
        // 캠프 좌표를 이벤트에 싣는다 — dispatch 의 라우트 출발·복귀 지점이고, 되묻는 동기
        // 호출은 불변규칙 4 가 금지한다. 캠프를 못 찾으면 이 웨이브만 실패시킨다.
        Camp camp = referenceData.findCamp(wave.campId()).orElseThrow(() -> new IllegalStateException(
                "캠프를 찾지 못해 wave.closed 를 낼 수 없습니다: campId=" + wave.campId()));
        events.waveClosed(wave, camp);
        return new Outcome.Closed(wave, camp.code());
    }

    /** 닫기의 결과. */
    public sealed interface Outcome {

        /**
         * 닫았다. 커밋 뒤에 호출자가 게이지를 올린다.
         *
         * @param wave     닫힌 웨이브
         * @param campCode 게이지 라벨용 캠프 코드 — id 가 아니라 코드다(§9.1, 라벨은 라벨마다 일관해야 한다)
         */
        record Closed(Wave wave, String campCode) implements Outcome {
        }

        /**
         * 이미 {@code OPEN} 이 아니다.
         *
         * @param wave 현재 상태의 웨이브
         */
        record NotOpen(Wave wave) implements Outcome {
        }

        /** 없는 웨이브. */
        record NotFound() implements Outcome {
        }
    }
}
