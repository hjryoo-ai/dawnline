package com.dawnline.sim.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.sim.config.SimProperties.Scenario;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * smoke 시나리오 — 페이싱·집계·판정. HTTP 없이 검증한다.
 *
 * <p>가짜 시계는 <strong>저절로 흐르지 않는다</strong>. 대기와 요청이 각자 시간을 밀어야
 * "요청이 오래 걸리면 페이싱이 어떻게 되는가" 를 실제로 시험할 수 있다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SmokeScenarioTest {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long ONE_MILLI = 1_000_000L;

    /** 명시적으로만 흐르는 단조 시계. */
    private static final class FakeNanoTime implements LongSupplier {
        private long now = NANOS_PER_SECOND;

        @Override
        public long getAsLong() {
            return now;
        }

        void advance(long nanos) {
            if (nanos > 0) {
                now += nanos;
            }
        }
    }

    /** 실제로 자는 대신 시계를 그만큼 민다. */
    private static final class RecordingSleeper implements Sleeper {
        private final FakeNanoTime clock;
        private final List<Long> requested = new ArrayList<>();
        private final List<Long> wokeAt = new ArrayList<>();

        RecordingSleeper(FakeNanoTime clock) {
            this.clock = clock;
        }

        @Override
        public void sleepNanos(long nanos) {
            requested.add(nanos);
            clock.advance(nanos);
            wokeAt.add(clock.now);
        }
    }

    /** 요청 하나가 {@code workNanos} 만큼 걸리는 클라이언트. */
    private static OrderClient client(FakeNanoTime clock, long workNanos, OrderClient delegate) {
        return (order, key) -> {
            clock.advance(workNanos);
            return delegate.place(order, key);
        };
    }

    private static Scenario scenario(int orders, int ratePerSecond) {
        return new Scenario(orders, ratePerSecond, 1L, 50, 0.25, Map.of("DAWN", 1));
    }

    private static OrderGenerator generator(Scenario scenario) {
        return new OrderGenerator(scenario,
                RandomGeneratorFactory.of("L64X128MixRandom").create(scenario.seed()));
    }

    @Test
    void 전부_201_이면_성공이다() throws InterruptedException {
        Scenario scenario = scenario(200, 20);
        FakeNanoTime clock = new FakeNanoTime();
        SmokeScenario smoke = new SmokeScenario(
                client(clock, ONE_MILLI, (order, key) -> OrderClient.Response.of(201, null)),
                new RecordingSleeper(clock), clock);

        ScenarioReport report = smoke.run("smoke", scenario, generator(scenario), "run");

        assertThat(report.isSuccess()).isTrue();
        assertThat(report.accepted()).isEqualTo(200);
        assertThat(report.requested()).isEqualTo(200);
        assertThat(report.problemCodes()).isEmpty();
    }

    @Test
    void 실패는_code_별로_집계되고_성공으로_넘어가지_않는다() throws InterruptedException {
        Scenario scenario = scenario(4, 20);
        FakeNanoTime clock = new FakeNanoTime();
        List<OrderClient.Response> responses = List.of(
                OrderClient.Response.of(201, null),
                OrderClient.Response.of(422, "tier-not-serviceable"),
                OrderClient.Response.of(500, null),
                OrderClient.Response.transportFailure("ConnectException"));
        int[] next = {0};
        SmokeScenario smoke = new SmokeScenario(
                client(clock, ONE_MILLI, (order, key) -> responses.get(next[0]++)),
                new RecordingSleeper(clock), clock);

        ScenarioReport report = smoke.run("smoke", scenario, generator(scenario), "run");

        assertThat(report.isSuccess()).isFalse();
        assertThat(report.accepted()).isEqualTo(1);
        assertThat(report.clientErrors()).isEqualTo(1);
        assertThat(report.serverErrors()).isEqualTo(1);
        assertThat(report.transportErrors()).isEqualTo(1);
        assertThat(report.problemCodes())
                .containsEntry("tier-not-serviceable", 1)
                // code 가 없는 5xx 는 status 로라도 남긴다. "모르겠다" 도 정보다.
                .containsEntry("status:500", 1)
                .containsEntry("transport:ConnectException", 1);
    }

    @Test
    void 재생_응답은_접수로_세지_않는다() throws InterruptedException {
        // 멱등 키가 겹치면 200 이 돌아온다. 그걸 접수로 세면 "200건 넣었다" 고 말한 뒤
        // DB 에는 아무것도 늘지 않은 상태가 된다.
        Scenario scenario = scenario(3, 20);
        FakeNanoTime clock = new FakeNanoTime();
        SmokeScenario smoke = new SmokeScenario(
                client(clock, ONE_MILLI, (order, key) -> OrderClient.Response.of(200, null)),
                new RecordingSleeper(clock), clock);

        ScenarioReport report = smoke.run("smoke", scenario, generator(scenario), "run");

        assertThat(report.replayed()).isEqualTo(3);
        assertThat(report.accepted()).isZero();
        assertThat(report.isSuccess()).isFalse();
    }

    @Test
    void 멱등_키는_실행_접두어와_순번으로_전부_다르다() throws InterruptedException {
        Scenario scenario = scenario(50, 20);
        FakeNanoTime clock = new FakeNanoTime();
        Set<String> keys = new LinkedHashSet<>();
        SmokeScenario smoke = new SmokeScenario(client(clock, ONE_MILLI, (order, key) -> {
            keys.add(key);
            return OrderClient.Response.of(201, null);
        }), new RecordingSleeper(clock), clock);

        smoke.run("smoke", scenario, generator(scenario), "run-abc");

        assertThat(keys).hasSize(50).allSatisfy(key -> assertThat(key).startsWith("run-abc-"));
    }

    @Test
    void 페이싱은_절대_시각을_겨냥해_밀리지_않는다() throws InterruptedException {
        // 다음 요청의 목표 시각은 언제나 "시작 + i × 간격" 이다. 직전 요청이 끝난 시각에
        // 간격을 더하면 요청 시간만큼 오차가 쌓여, 200건이 계획보다 한참 늦게 끝난다.
        Scenario scenario = scenario(5, 20);
        FakeNanoTime clock = new FakeNanoTime();
        RecordingSleeper sleeper = new RecordingSleeper(clock);
        long startedAt = clock.now;
        SmokeScenario smoke = new SmokeScenario(
                client(clock, ONE_MILLI, (order, key) -> OrderClient.Response.of(201, null)),
                sleeper, clock);

        smoke.run("smoke", scenario, generator(scenario), "run");

        long interval = NANOS_PER_SECOND / 20;
        assertThat(sleeper.wokeAt).hasSize(5);
        for (int i = 0; i < sleeper.wokeAt.size(); i++) {
            assertThat(sleeper.wokeAt.get(i)).isEqualTo(startedAt + i * interval);
        }
    }

    @Test
    void 요청이_간격보다_느리면_대기하지_않고_바로_다음을_보낸다() throws InterruptedException {
        // 느려진 서버 앞에서 페이싱이 부하를 <em>줄이지</em> 않는다는 것을 고정한다.
        Scenario scenario = scenario(5, 20);            // 간격 50ms
        FakeNanoTime clock = new FakeNanoTime();
        RecordingSleeper sleeper = new RecordingSleeper(clock);
        SmokeScenario smoke = new SmokeScenario(
                client(clock, 60 * ONE_MILLI, (order, key) -> OrderClient.Response.of(201, null)),
                sleeper, clock);

        smoke.run("smoke", scenario, generator(scenario), "run");

        assertThat(sleeper.requested).allSatisfy(nanos -> assertThat(nanos).isNotPositive());
    }

    @Test
    void 지연_요약은_표본에서_나온다() throws InterruptedException {
        Scenario scenario = scenario(10, 20);
        FakeNanoTime clock = new FakeNanoTime();
        SmokeScenario smoke = new SmokeScenario(
                client(clock, 7 * ONE_MILLI, (order, key) -> OrderClient.Response.of(201, null)),
                new RecordingSleeper(clock), clock);

        ScenarioReport report = smoke.run("smoke", scenario, generator(scenario), "run");

        assertThat(report.latency().p50()).isEqualTo(7.0);
        assertThat(report.latency().max()).isEqualTo(7.0);
        assertThat(report.elapsed().toNanos()).isPositive();
    }
}
