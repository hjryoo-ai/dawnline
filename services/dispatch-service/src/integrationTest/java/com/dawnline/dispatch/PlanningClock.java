package com.dawnline.dispatch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 계획을 도는 통합 테스트의 시계 — <strong>옮기되 멈추지 않는다</strong>.
 *
 * <h2>왜 옮기는가</h2>
 * 차량 근무창은 벽시계 {@code TIME}(06:00–22:00 KST)이고 어댑터가 <em>계획 날짜</em>에 붙인다
 * ({@code JdbcReferenceData.availableAt}). 실행 시각이 21시면 남은 근무창이 한 시간이라 오전에
 * 돌린 것과 결과가 완전히 다르다 — 배정이 미배정으로 바뀌고, 그러면 "주문당 하나" 같은 어설션이
 * 시각에 따라 붙었다 떨어진다. seed 를 고정하고 주문 id 를 결정적으로 만들어도 이 축은 남는다.
 * 09:00 KST 로 옮기면 근무창 한가운데에서 시작해 축이 사라진다.
 *
 * <h2>왜 멈추지 않는가</h2>
 * {@link Clock#fixed} 로 세우면 서비스가 재는 {@code planDurationMs} 가 <strong>0 이 된다</strong>
 * (끝난 시각 − 시작 시각이므로). 그러면 "§6.7 목표 30초 이하" 어설션이 <em>언제나</em> 참이 되어
 * 아무것도 검사하지 않는다 — 이 저장소가 세 번 데었던 공허한 테스트가 하나 더 는다. 그래서
 * {@link Clock#offset} 으로 <em>위치만</em> 옮기고 흐름은 그대로 둔다.
 *
 * <p>{@link Clock#tick} 으로 마이크로초에 맞추는 이유는 {@code libs/messaging} 의 기본 시계와 같은
 * 정밀도를 쓰기 위해서다 — 저장 정밀도보다 고운 시각은 읽어 올 때 달라진다.
 *
 * <p><strong>테스트 픽스처도 이 시각을 써야 한다.</strong> 시계만 옮기고 후보의 약속 창을
 * {@code Instant.now()} 로 만들면 둘이 어긋나서 같은 문제가 다른 얼굴로 돌아온다
 * (2026-09-05 에 {@code PlanExecutionIT} 가 그랬다 — 21시에 돌리면 약속 창이 근무창 밖이었다).
 */
@TestConfiguration
public class PlanningClock {

    /** 2026-09-05 09:00 KST — 근무창(06:00–22:00) 한가운데. */
    public static final Instant PLAN_AT = Instant.parse("2026-09-05T00:00:00Z");

    /**
     * @return 09:00 KST 로 옮긴 <strong>흐르는</strong> 시계.
     *         {@code @ConditionalOnMissingBean} 이라 이 빈이 자동설정을 이긴다
     */
    @Bean
    Clock dawnlineClock() {
        Clock system = Clock.systemUTC();
        return Clock.tick(Clock.offset(system, Duration.between(system.instant(), PLAN_AT)),
                Duration.ofNanos(1_000));
    }
}
