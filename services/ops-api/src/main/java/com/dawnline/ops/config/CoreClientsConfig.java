package com.dawnline.ops.config;

import com.dawnline.ops.adapter.out.core.AuditIdPropagation;
import com.dawnline.ops.adapter.out.core.CoreCommandsClient;
import com.dawnline.ops.adapter.out.core.InternalTokenPropagation;
import com.dawnline.ops.adapter.out.core.dispatch.api.PlanControllerApi;
import com.dawnline.ops.adapter.out.core.dispatch.api.RouteControllerApi;
import com.dawnline.ops.adapter.out.core.fulfillment.api.WaveControllerApi;
import com.dawnline.ops.adapter.out.core.order.api.OrderControllerApi;
import com.dawnline.web.internal.InternalTokenProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * 코어 위임 클라이언트 배선 (DESIGN.md §5.5 「커맨드 위임」, ADR-052).
 *
 * <p>인터페이스는 커밋된 계약에서 빌드 때 생성되고, 여기서는 그것을 Boot 4 의 HTTP Service Client 로
 * 등록만 한다 — 그룹 이름이 {@code spring.http.serviceclient.<그룹>} 의 base-url·타임아웃과 이어진다
 * (application.yml). 그룹의 {@code RestClient} 는 Boot 가 관측을 붙여 만들므로 트레이스가 코어로 이어진다.
 *
 * <p>그룹은 코어 서비스 단위다 — 넷. {@code tracking} 은 outbox 관리 경로만 쓴다(스캔은 현장 표면이라 위임하지 않는다).
 * outbox 관리 인터페이스는 코어마다 자기 패키지에 생성되므로(같은 이름, 다른 타입) 여기서만 정규화된 이름으로 적는다.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "dispatch", types = {PlanControllerApi.class, RouteControllerApi.class,
        com.dawnline.ops.adapter.out.core.dispatch.api.OutboxAdminControllerApi.class})
@ImportHttpServices(group = "order", types = {OrderControllerApi.class,
        com.dawnline.ops.adapter.out.core.order.api.OutboxAdminControllerApi.class})
@ImportHttpServices(group = "fulfillment", types = {WaveControllerApi.class,
        com.dawnline.ops.adapter.out.core.fulfillment.api.OutboxAdminControllerApi.class})
@ImportHttpServices(group = "tracking", types = com.dawnline.ops.adapter.out.core.tracking.api.OutboxAdminControllerApi.class)
public class CoreClientsConfig {

    /**
     * 모든 그룹의 클라이언트에 감사 id 상관 헤더와 내부 토큰을 단다 (§5.5, ADR-055 결정 4). 한 자리에 두는 이유:
     * 그룹이 늘 때(작업 2 의 fulfillment·tracking) 둘 중 하나만 붙는 일이 없다.
     *
     * @param internalToken 검증된 내부 토큰 설정
     * @return 그룹 설정
     */
    @Bean
    public RestClientHttpServiceGroupConfigurer coreCallHeaders(InternalTokenProperties internalToken) {
        AuditIdPropagation auditId = new AuditIdPropagation();
        InternalTokenPropagation token = new InternalTokenPropagation(internalToken);
        return groups -> groups.forEachClient((group, builder) -> builder
                .requestInterceptor(auditId)
                .requestInterceptor(token));
    }

    /**
     * @return 코어 위임과 조회 — 한 어댑터가 둘을 구현한다
     */
    @Bean
    public CoreCommandsClient coreCommands(PlanControllerApi plans, RouteControllerApi routes, OrderControllerApi orders,
            WaveControllerApi waves,
            com.dawnline.ops.adapter.out.core.order.api.OutboxAdminControllerApi orderOutbox,
            com.dawnline.ops.adapter.out.core.fulfillment.api.OutboxAdminControllerApi fulfillmentOutbox,
            com.dawnline.ops.adapter.out.core.dispatch.api.OutboxAdminControllerApi dispatchOutbox,
            com.dawnline.ops.adapter.out.core.tracking.api.OutboxAdminControllerApi trackingOutbox) {
        return CoreCommandsClient.of(plans, routes, orders, waves, orderOutbox, fulfillmentOutbox, dispatchOutbox,
                trackingOutbox);
    }
}
