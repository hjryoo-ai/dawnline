package com.dawnline.sim.order;

import com.dawnline.sim.order.CancelPlan.When;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 한 실행의 취소 — 계획 전은 접수 직후에 보내고, 발행 뒤는 모았다가 보낸다 (7-4 turbulent).
 *
 * <p>결과는 <strong>때와 응답별로</strong> 센다. 발행 뒤 취소의 409 는 실패가 아니라 측정이다 — order-service 가 {@code DISPATCHED} 를
 * 먼저 알았다는 뜻이고, 200 의 몫이 {@code order.dispatched} 가 반영되기 전의 창이다(7-0 A18).
 */
public final class OrderCancellations {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final CancelPlan plan;
    private final OrderCancelClient client;
    private final Sleeper sleeper;
    private final LongSupplier nanoTime;
    private final double ratePerSecond;
    private final List<UUID> held = new ArrayList<>();
    private final Map<String, Integer> beforePlan = new TreeMap<>();
    private final Map<String, Integer> afterPublish = new TreeMap<>();
    private int beforePlanDecided;
    private int afterPublishDecided;
    private int unknownId;

    /**
     * @param plan          어느 주문을 언제
     * @param client        취소 클라이언트
     * @param sleeper       발행 뒤 취소의 페이싱
     * @param nanoTime      단조 시계 (불변규칙 12)
     * @param ratePerSecond 발행 뒤 취소의 속도
     */
    public OrderCancellations(CancelPlan plan, OrderCancelClient client, Sleeper sleeper, LongSupplier nanoTime,
            double ratePerSecond) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.client = Objects.requireNonNull(client, "client");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.ratePerSecond = ratePerSecond;
    }

    /** 다음 주문의 결정 — 보내기 전에, 주문마다 한 번. */
    When decide(GeneratedOrder order) {
        return plan.next(order);
    }

    /** 접수된 주문을 결정대로 다룬다. 계획 전이면 곧바로 취소한다. */
    synchronized void onAccepted(When when, OrderClient.Response response) {
        if (when == When.NONE) {
            return;
        }
        if (when == When.BEFORE_PLAN) {
            beforePlanDecided++;
        } else {
            afterPublishDecided++;
        }
        UUID orderId = response.orderId();
        if (orderId == null) {
            // 201 본문에서 id 를 읽지 못했다 — 부를 수 없다. 조용히 빼지 않고 센다.
            unknownId++;
            return;
        }
        if (when == When.BEFORE_PLAN) {
            count(beforePlan, client.cancel(orderId));
        } else {
            held.add(orderId);
        }
    }

    /**
     * 발행 뒤 취소를 보낸다 — 계획이 끝난 직후에 부른다. 속도를 지킨다: 한 번에 몰면 기사가 출발하기 전에 끝나 창의 한 끝만 본다.
     *
     * @throws InterruptedException 대기 중 인터럽트
     */
    public void sendAfterPublish() throws InterruptedException {
        List<UUID> toSend;
        synchronized (this) {
            toSend = List.copyOf(held);
            held.clear();
        }
        long interval = Math.round(NANOS_PER_SECOND / ratePerSecond);
        long started = nanoTime.getAsLong();
        for (int i = 0; i < toSend.size(); i++) {
            sleeper.sleepNanos(started + i * interval - nanoTime.getAsLong());
            OrderClient.Response response = client.cancel(toSend.get(i));
            synchronized (this) {
                count(afterPublish, response);
            }
        }
    }

    /** @return 지금까지의 결과 */
    public synchronized CancelReport report() {
        return new CancelReport(beforePlanDecided, Map.copyOf(beforePlan), afterPublishDecided, Map.copyOf(afterPublish),
                held.size(), unknownId);
    }

    private static void count(Map<String, Integer> tally, OrderClient.Response response) {
        String key = response.status() == 0 ? "transport:" + response.failure()
                : response.problemCode() == null ? String.valueOf(response.status())
                : response.status() + " " + response.problemCode();
        tally.merge(key, 1, Integer::sum);
    }
}
