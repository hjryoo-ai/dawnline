package com.dawnline.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.outbox.OutboxQuarantine;
import com.dawnline.messaging.outbox.OutboxRepository;
import com.dawnline.messaging.support.InMemoryOutboxRepository;
import com.dawnline.messaging.support.MutableClock;
import com.dawnline.messaging.support.TestTransactionManager;
import com.dawnline.messaging.web.OutboxAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 격리 조회·재큐 엔드포인트가 <strong>조건으로</strong> 켜지는가 (ADR-015 후속 정정 결정 1·4).
 *
 * <p>서비스가 켜는 스위치가 없다는 것이 요점이다 — outbox 가 있는 서블릿 웹 앱이면 붙는다. 끄는 스위치는 ops-api 를
 * 위한 것 하나다.
 */
class OutboxAdminAutoConfigurationTest {

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAdminAutoConfiguration.class));

    @Test
    void outbox_가_있는_서블릿_웹_앱이면_서비스가_아무것도_적지_않아도_붙는다() {
        web.withBean(OutboxRepository.class, () -> new InMemoryOutboxRepository())
                .withBean(PlatformTransactionManager.class, TestTransactionManager::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxQuarantine.class);
                    assertThat(context).hasSingleBean(OutboxAdminController.class);
                });
    }

    @Test
    void outbox_가_없으면_붙지_않는다() {
        web.withBean(PlatformTransactionManager.class, TestTransactionManager::new)
                .run(context -> assertThat(context).doesNotHaveBean(OutboxAdminController.class));
    }

    @Test
    void 웹이_아니면_붙지_않는다() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboxAdminAutoConfiguration.class))
                .withBean(OutboxRepository.class, () -> new InMemoryOutboxRepository())
                .withBean(PlatformTransactionManager.class, TestTransactionManager::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxRepository.class);
                    assertThat(context).doesNotHaveBean(OutboxAdminController.class);
                });
    }

    @Test
    void admin_api_를_끄면_outbox_가_있어도_붙지_않는다() {
        // ops-api 의 자리다 — 조건은 맞지만 감사 없는 재큐가 생긴다(결정 4).
        web.withBean(OutboxRepository.class, () -> new InMemoryOutboxRepository())
                .withBean(PlatformTransactionManager.class, TestTransactionManager::new)
                .withPropertyValues("dawnline.messaging.outbox.admin-api=false")
                .run(context -> {
                    assertThat(context).as("전제 — 조건의 나머지는 맞는다").hasSingleBean(OutboxRepository.class);
                    assertThat(context).doesNotHaveBean(OutboxAdminController.class);
                    assertThat(context).doesNotHaveBean(OutboxQuarantine.class);
                });
    }
}
