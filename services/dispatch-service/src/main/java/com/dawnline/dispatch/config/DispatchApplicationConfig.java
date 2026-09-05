package com.dawnline.dispatch.config;

import com.dawnline.dispatch.adapter.in.messaging.FulfillmentPlannedListener;
import com.dawnline.dispatch.adapter.in.messaging.WaveClosedListener;
import com.dawnline.dispatch.adapter.out.messaging.OutboxDispatchEvents;
import com.dawnline.dispatch.adapter.out.persistence.JdbcPlannedRouteRepository;
import com.dawnline.dispatch.adapter.out.persistence.JdbcReferenceData;
import com.dawnline.dispatch.adapter.out.persistence.JpaRoutePlanRepository;
import com.dawnline.dispatch.adapter.out.persistence.JpaDispatchCandidateRepository;
import com.dawnline.dispatch.application.LoadCandidateService;
import com.dawnline.dispatch.application.RecoverStalePlansService;
import com.dawnline.dispatch.application.RunPlanService;
import com.dawnline.dispatch.application.port.in.LoadCandidateUseCase;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.application.port.out.DispatchEvents;
import com.dawnline.dispatch.application.port.out.PlannedRouteRepository;
import com.dawnline.dispatch.application.port.out.RoutePlanRepository;
import com.dawnline.dispatch.domain.optimizer.DistanceProvider;
import com.dawnline.dispatch.domain.optimizer.HaversineDistance;
import com.dawnline.dispatch.domain.optimizer.PlanningBudget;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.outbox.OutboxAppender;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/**
 * 유스케이스 배선 (DESIGN.md §5.3).
 *
 * <p>도메인·유스케이스 클래스에는 Spring 어노테이션이 없다(불변규칙 5) — 특히
 * {@code domain.optimizer} 는 {@code tools/benchmark} 가 서비스 없이 그대로 실행한다.
 * 그래서 배선이 여기 모여 있고, 무엇이 무엇에 기대는지가 한 파일에 보인다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DispatchProperties.class)
@EnableScheduling
public class DispatchApplicationConfig {


    /**
     * {@code dispatch_candidates} 저장소.
     *
     * <p>Spring Data 리포지토리를 쓰지 않는다. 다른 서비스와 같은 이유다 — 생성되는 쿼리가
     * 소스에 그대로 있어야 하고, 여기서는 특히 그렇다({@code ON CONFLICT}, 인덱스를 타는 리터럴).
     *
     * @param entityManagerFactory EMF
     */
    @Bean
    public DispatchCandidateRepository dispatchCandidateRepository(
            EntityManagerFactory entityManagerFactory) {
        return new JpaDispatchCandidateRepository(
                SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
    }

    /**
     * @param candidates 후보 저장소
     * @param clock      시각 출처 (불변규칙 12)
     */
    @Bean
    public LoadCandidateUseCase loadCandidateUseCase(DispatchCandidateRepository candidates,
            Clock clock) {
        return new LoadCandidateService(candidates, clock);
    }

    /**
     * @param consumer      멱등 게이트
     * @param loadCandidate 적재 유스케이스
     * @param json          봉투 역직렬화
     */
    @Bean
    public FulfillmentPlannedListener fulfillmentPlannedListener(IdempotentConsumer consumer,
            LoadCandidateUseCase loadCandidate, EventJson json) {
        return new FulfillmentPlannedListener(consumer, loadCandidate, json);
    }

    /**
     * 참조 데이터. 네 포트를 한 구현이 채운다 — 넷 다 같은 표를 읽는다.
     *
     * @param entityManagerFactory EMF
     */
    @Bean
    public JdbcReferenceData referenceData(EntityManagerFactory entityManagerFactory) {
        return new JdbcReferenceData(
                SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
    }

    /**
     * @param entityManagerFactory EMF
     */
    @Bean
    public RoutePlanRepository routePlanRepository(EntityManagerFactory entityManagerFactory) {
        return new JpaRoutePlanRepository(
                SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
    }

    /**
     * @param entityManagerFactory EMF
     */
    @Bean
    public PlannedRouteRepository plannedRouteRepository(EntityManagerFactory entityManagerFactory) {
        return new JdbcPlannedRouteRepository(
                SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
    }

    /**
     * @param outbox  outbox 적재
     * @param drivers 차량 → 기사
     * @param clock   시각 출처
     */
    @Bean
    public DispatchEvents dispatchEvents(OutboxAppender outbox, JdbcReferenceData drivers,
            Clock clock) {
        return new OutboxDispatchEvents(outbox, drivers, clock);
    }

    /**
     * 거리 제공자 (§6.2). 파라미터는 캠프 설정값이라 코드에 상수를 두지 않는다.
     *
     * @param properties {@code dawnline.dispatch.distance.*}
     */
    @Bean
    public DistanceProvider distanceProvider(DispatchProperties properties) {
        return new HaversineDistance(properties.distance().roadFactor(),
                properties.distance().averageSpeedKmh());
    }

    /**
     * @param plans      계획 저장소
     * @param candidates 후보 저장소
     * @param routes     라우트 저장소
     * @param events     발행
     * @param reference  차량·룰·캠프
     * @param distance   거리 제공자
     * @param clock      시각 출처
     * @param properties {@code dawnline.dispatch.plan.*}
     */
    @Bean
    public RunPlanUseCase runPlanUseCase(RoutePlanRepository plans,
            DispatchCandidateRepository candidates, PlannedRouteRepository routes,
            DispatchEvents events, JdbcReferenceData reference, DistanceProvider distance,
            Clock clock, DispatchProperties properties) {

        return new RunPlanService(plans, candidates, routes, events, reference, reference,
                distance, clock, properties.plan().defaultStrategy(),
                new PlanningBudget(properties.plan().budget(), properties.plan().perRouteBudget()));
    }

    /**
     * @param consumer 멱등 게이트
     * @param runPlan  계획 유스케이스
     * @param json     봉투 역직렬화
     */
    @Bean
    public WaveClosedListener waveClosedListener(IdempotentConsumer consumer,
            RunPlanUseCase runPlan, EventJson json) {
        return new WaveClosedListener(consumer, runPlan, json);
    }

    /**
     * 정체 회수 (§5.3). {@code PLANNING} 으로 남은 계획의 유일한 출구다.
     *
     * @param plans              계획 저장소
     * @param runPlan            재실행할 유스케이스
     * @param transactionManager 회수 트랜잭션
     * @param clock              시각 출처
     * @param properties         {@code dawnline.dispatch.plan.*}
     */
    @Bean
    public RecoverStalePlansService recoverStalePlansService(RoutePlanRepository plans,
            RunPlanUseCase runPlan, PlatformTransactionManager transactionManager, Clock clock,
            DispatchProperties properties) {

        return new RecoverStalePlansService(plans, runPlan, transactionManager, clock,
                properties.plan().staleAfter(), properties.plan().recoverBatch());
    }
}
