package com.dawnline.dispatch.config;

import com.dawnline.dispatch.adapter.in.messaging.FulfillmentPlannedListener;
import com.dawnline.dispatch.adapter.out.persistence.JpaDispatchCandidateRepository;
import com.dawnline.dispatch.application.LoadCandidateService;
import com.dawnline.dispatch.application.port.in.LoadCandidateUseCase;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/**
 * 유스케이스 배선 (DESIGN.md §5.3).
 *
 * <p>도메인·유스케이스 클래스에는 Spring 어노테이션이 없다(불변규칙 5) — 특히
 * {@code domain.optimizer} 는 {@code tools/benchmark} 가 서비스 없이 그대로 실행한다.
 * 그래서 배선이 여기 모여 있고, 무엇이 무엇에 기대는지가 한 파일에 보인다.
 */
@Configuration(proxyBeanMethods = false)
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
}
