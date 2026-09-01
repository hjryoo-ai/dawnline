package com.dawnline.observability.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.observability.MdcFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

/**
 * 자동 구성이 실제로 MDC 필터를 등록하는지, 그리고 웹이 아닌 애플리케이션에서는
 * 조용히 빠지는지 확인한다.
 */
class ObservabilityAutoConfigurationTest {

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    @Test
    void 서블릿웹애플리케이션이면_MDC필터가등록된다() {
        webRunner.withPropertyValues("spring.application.name=dispatch-service")
                .run(context -> {
                    assertThat(context).hasSingleBean(FilterRegistrationBean.class);
                    FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
                    assertThat(registration.getFilter()).isInstanceOf(MdcFilter.class);
                    assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
                });
    }

    @Test
    void 웹애플리케이션이아니면_필터를등록하지않는다() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
    }

    @Test
    void spring_application_name이없어도_기동한다() {
        webRunner.run(context -> assertThat(context).hasSingleBean(FilterRegistrationBean.class));
    }
}
