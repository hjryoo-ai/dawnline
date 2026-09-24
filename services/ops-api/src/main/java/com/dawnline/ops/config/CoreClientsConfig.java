package com.dawnline.ops.config;

import com.dawnline.ops.adapter.out.core.AuditIdPropagation;
import com.dawnline.ops.adapter.out.core.CoreCommandsClient;
import com.dawnline.ops.adapter.out.core.dispatch.api.PlanControllerApi;
import com.dawnline.ops.adapter.out.core.dispatch.api.RouteControllerApi;
import com.dawnline.ops.adapter.out.core.order.api.OrderControllerApi;
import com.dawnline.ops.application.port.out.CoreCommands;
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
 * <p>그룹은 코어 서비스 단위다. 웨이브 조기 마감이 붙으면(작업 2) {@code fulfillment} 그룹이 는다.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "dispatch", types = {PlanControllerApi.class, RouteControllerApi.class})
@ImportHttpServices(group = "order", types = OrderControllerApi.class)
public class CoreClientsConfig {

    /**
     * 모든 그룹의 클라이언트에 감사 id 상관 헤더를 단다.
     *
     * @return 그룹 설정
     */
    @Bean
    public RestClientHttpServiceGroupConfigurer auditIdPropagation() {
        AuditIdPropagation interceptor = new AuditIdPropagation();
        return groups -> groups.forEachClient((group, builder) -> builder.requestInterceptor(interceptor));
    }

    /**
     * @param plans  dispatch 계획
     * @param routes dispatch 라우트
     * @param orders order 주문
     * @return 코어 위임
     */
    @Bean
    public CoreCommands coreCommands(PlanControllerApi plans, RouteControllerApi routes, OrderControllerApi orders) {
        return new CoreCommandsClient(plans, routes, orders);
    }
}
