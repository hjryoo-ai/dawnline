package com.dawnline.messaging.config;

import com.dawnline.messaging.outbox.OutboxQuarantine;
import com.dawnline.messaging.outbox.OutboxRepository;
import com.dawnline.messaging.web.OutboxAdminController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * outbox 격리 조회·재큐 엔드포인트 배선 (DESIGN.md §4.6 「격리 조회·재큐 엔드포인트」, ADR-015 후속 정정).
 *
 * <h2>조건이 켠다 — 서비스가 켜지 않는다</h2>
 * {@code OutboxRepository} 빈이 있고 서블릿 웹 앱이면 붙는다. 서비스마다 스위치를 적게 하면 새 서비스가 한 줄을
 * 잊었을 때 <strong>조용히 빠진다</strong> — 격리 알림이 울린 날 그 서비스만 SQL 로 돌아가야 한다는 것을 그날
 * 알게 된다.
 *
 * <h2>끄는 스위치는 있다 — 한 서비스를 위해</h2>
 * {@code dawnline.messaging.outbox.admin-api=false}. ops-api 가 쓴다: 조건은 맞지만(자기 outbox 가 있다) ops-api 의
 * 운영 표면은 전부 감사 행을 남기는데 이 경로는 남기지 않는다. 이 스위치를 잊는 방향은 <em>열리는</em> 쪽이라
 * 조용하지 않다 — ops-api 의 테스트가 그 부재를 본다.
 *
 * <p>{@code DispatcherServlet} 을 <strong>이름으로</strong> 조건에 건다. 웹이 없는 소비자의 클래스패스에는
 * {@code spring-webmvc} 가 없고, 이 클래스의 조건은 클래스를 로드하기 전에 바이트코드로 평가된다.
 */
@AutoConfiguration(after = MessagingJpaAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnBean({OutboxRepository.class, PlatformTransactionManager.class})
@ConditionalOnProperty(prefix = "dawnline.messaging.outbox", name = "admin-api", havingValue = "true",
        matchIfMissing = true)
public class OutboxAdminAutoConfiguration {

    /**
     * 조회·재큐.
     *
     * @param repository         outbox 저장소
     * @param transactionManager 이 서비스 DB 의 트랜잭션 관리자
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxQuarantine dawnlineOutboxQuarantine(OutboxRepository repository,
            PlatformTransactionManager transactionManager) {
        return new OutboxQuarantine(repository, transactionManager);
    }

    /**
     * {@code @Bean} 으로 등록해도 핸들러 매핑이 잡는다 — 매핑은 빈 <em>타입</em>의 {@code @Controller} 를 본다.
     *
     * @param quarantine 조회·재큐
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxAdminController dawnlineOutboxAdminController(OutboxQuarantine quarantine) {
        return new OutboxAdminController(quarantine);
    }
}
