package com.dawnline.sim.driver;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 여정의 현재 위치 — {@link DriverSimulator} 를 <strong>재진입 가능</strong>하게 만드는 값.
 *
 * <p>시뮬레이터가 「라우트 → 스캔 열」이 아니라 「라우트 + 현재 위치 → <em>남은</em> 스캔 열」인
 * 이유는 개정이다. 재계획이 오면 이 값으로 같은 순수 함수를 다시 부르면 되고, 그러면
 * <strong>기사가 새 순서를 따르는지</strong>가 tracking DB 가 아니라 도구의 출력에서 보인다
 * (Phase 5-3 DoD 「at-risk → 재계획 → revision 반영」의 마지막 한 칸).
 *
 * <h2>종결은 주문으로 센다</h2>
 * {@code seq} 가 아니다. 개정이 순서를 바꾸면 같은 {@code seq} 가 다른 지점을 가리키지만
 * 주문 id 는 그대로다. tracking 이 「개정은 종결을 되돌리지 않는다」를 배송(=주문) 단위로
 * 판단하는 것과 같은 축이다 — 축이 다르면 두 쪽의 「이미 끝났다」가 어긋난다.
 *
 * @param readyAt         다음 이동을 시작할 수 있는 시뮬레이션 시각. {@code null} 이면 아직
 *                        캠프를 떠나지 않았다
 * @param completedOrders 이미 전달 완료·실패로 끝낸 주문들
 */
public record TripProgress(@Nullable Instant readyAt, Set<UUID> completedOrders) {

    public TripProgress {
        completedOrders = Set.copyOf(completedOrders);
    }

    /** 아직 캠프에 있다. */
    public static TripProgress start() {
        return new TripProgress(null, Set.of());
    }

    /** 캠프를 떠났는가. */
    public boolean departed() {
        return readyAt != null;
    }

    /**
     * 스캔 하나를 보낸 뒤의 위치.
     *
     * <p>끝낸 주문은 <strong>스캔 자신이</strong> 말한다 — 개정에서 {@code seq} 로 되찾지
     * 않는다. 되찾는 형태는 「보낸 것」과 「셌다고 적는 것」이 같은 축이 아니어서, 그 사이에
     * 개정이 끼면 조용히 다른 stop 의 주문을 끝났다고 적는다 (ADR-047 결정 1).
     *
     * @param call 보낸 스캔
     * @return 다음 위치
     */
    public TripProgress after(ScanCall call) {
        if (!call.type().isTerminal()) {
            return new TripProgress(call.occurredAt(), completedOrders);
        }
        Set<UUID> done = new LinkedHashSet<>(completedOrders);
        done.addAll(call.orderIds());
        return new TripProgress(call.occurredAt(), done);
    }
}
