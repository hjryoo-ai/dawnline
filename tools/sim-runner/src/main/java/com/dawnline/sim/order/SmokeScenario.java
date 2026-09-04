package com.dawnline.sim.order;

import com.dawnline.sim.config.SimProperties.Scenario;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code smoke} 시나리오 — 주문 N건을 일정한 속도로 보낸다 (IMPLEMENTATION_PLAN Phase 1-9).
 *
 * <h2>이것은 부하 테스트가 아니다</h2>
 * 부하는 k6 가 잰다({@code tools/k6/orders.js}). 여기서 속도를 두는 것은 <strong>흐름</strong>을
 * 만들기 위해서다 — 200건이 한꺼번에 쏟아지면 Phase 2 의 웨이브 편입이나 Phase 3 의 계획이
 * 실제 하루와 전혀 다른 모양을 보게 된다. 그래서 지연 요약도 내지만 그것은 참고값이지 SLO 가
 * 아니다.
 *
 * <h2>실패를 삼키지 않는다</h2>
 * 한 건이 실패해도 계속 보내되, 끝에 무엇이 몇 건 실패했는지를 코드별로 말한다. 그리고
 * 하나라도 접수되지 않으면 실행 자체가 실패로 끝난다 — {@code make demo} 가 "성공했다" 고
 * 말한 뒤에 DB 가 비어 있는 상황을 만들지 않기 위해서다.
 */
public final class SmokeScenario {

    private static final Logger log = LoggerFactory.getLogger(SmokeScenario.class);

    /** 진행 로그를 남기는 간격(건). */
    private static final int PROGRESS_EVERY = 50;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final OrderClient client;
    private final Sleeper sleeper;
    private final LongSupplier nanoTime;

    /**
     * @param client   주문 접수 클라이언트
     * @param sleeper  페이싱용 대기
     * @param nanoTime 단조 시계. 경과 시간에 벽시계를 쓰면 시간 조정에 흔들린다 (불변규칙 12)
     */
    public SmokeScenario(OrderClient client, Sleeper sleeper, LongSupplier nanoTime) {
        this.client = Objects.requireNonNull(client, "client");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * 시나리오를 실행한다.
     *
     * @param name      시나리오 이름 (보고용)
     * @param scenario  설정
     * @param generator 주문 생성기
     * @param runId     이 실행의 식별자. 멱등 키 접두어가 된다 — 실행이 겹쳐도 키가 겹치지 않고,
     *                  겹치면 201 이 아니라 200 재생이 되어 아무것도 새로 접수되지 않는다
     * @throws InterruptedException 대기 중 인터럽트
     */
    public ScenarioReport run(String name, Scenario scenario, OrderGenerator generator, String runId)
            throws InterruptedException {

        long intervalNanos = NANOS_PER_SECOND / scenario.ratePerSecond();
        long[] latencies = new long[scenario.orders()];
        Map<String, Integer> problemCodes = new HashMap<>();
        int accepted = 0;
        int replayed = 0;
        int clientErrors = 0;
        int serverErrors = 0;
        int transportErrors = 0;

        log.info("시나리오 '{}' 시작: {}건 × {} rps, seed={}",
                name, scenario.orders(), scenario.ratePerSecond(), scenario.seed());

        long startedAt = nanoTime.getAsLong();
        for (int i = 0; i < scenario.orders(); i++) {
            // 다음 요청의 목표 시각. 직전 요청이 느렸다고 그만큼 밀리면 전체가 점점 느려진다.
            long dueAt = startedAt + i * intervalNanos;
            sleeper.sleepNanos(dueAt - nanoTime.getAsLong());

            long sentAt = nanoTime.getAsLong();
            OrderClient.Response response = client.place(generator.next(i), runId + "-" + i);
            latencies[i] = nanoTime.getAsLong() - sentAt;

            if (response.isAccepted()) {
                accepted++;
            } else if (response.status() == 200) {
                replayed++;
            } else if (response.status() == 0) {
                transportErrors++;
                count(problemCodes, "transport:" + String.valueOf(response.failure()));
            } else if (response.status() >= 500) {
                serverErrors++;
                count(problemCodes, codeOf(response));
            } else {
                clientErrors++;
                count(problemCodes, codeOf(response));
            }

            if ((i + 1) % PROGRESS_EVERY == 0) {
                log.info("  {}/{} 건", i + 1, scenario.orders());
            }
        }

        return new ScenarioReport(name, scenario.orders(), accepted, replayed,
                clientErrors, serverErrors, transportErrors, problemCodes,
                ScenarioReport.Latency.of(latencies),
                Duration.ofNanos(nanoTime.getAsLong() - startedAt));
    }

    /** 코드가 없으면 status 로 표기한다 — "무엇 때문인지 모르겠다" 도 정보다. */
    private static String codeOf(OrderClient.Response response) {
        String code = response.problemCode();
        return code == null || code.isBlank() ? "status:" + response.status() : code;
    }

    private static void count(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }
}
