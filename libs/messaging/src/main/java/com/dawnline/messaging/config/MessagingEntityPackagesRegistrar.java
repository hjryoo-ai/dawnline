package com.dawnline.messaging.config;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

/**
 * {@code com.dawnline.messaging} 을 엔티티 스캔 대상에 <strong>추가</strong>한다.
 *
 * <h2>왜 {@code @EntityScan} 이 아닌가</h2>
 *
 * Boot 의 {@code JpaBaseConfiguration#getPackagesToScan()} 은 다음처럼 동작한다.
 *
 * <pre>
 * packages = EntityScanPackages.get(beanFactory).getPackageNames();
 * if (packages.isEmpty() &amp;&amp; AutoConfigurationPackages.has(beanFactory)) {
 *     packages = AutoConfigurationPackages.get(beanFactory);
 * }
 * </pre>
 *
 * 즉 {@code @EntityScan} 이 <em>하나라도</em> 있으면 애플리케이션의 자동설정 패키지는 <strong>무시</strong>된다.
 * 라이브러리가 {@code @EntityScan("com.dawnline.messaging")} 을 선언하는 순간 order-service 의
 * {@code com.dawnline.order} 엔티티가 스캔 대상에서 빠져 버린다. 조용히, 그리고 전부.
 *
 * <p>반면 {@link AutoConfigurationPackages#register} 는 이미 등록된 목록에 <strong>덧붙인다</strong>.
 * 애플리케이션의 기본 패키지는 그대로 두고 우리 패키지만 더한다.
 *
 * <p>실행 시점도 맞는다. {@code @SpringBootApplication} 의 {@code @AutoConfigurationPackage} 가
 * 먼저 처리되어 목록을 만들고, 그 뒤 자동설정 클래스들이 파싱되면서 이 등록자가 돈다.
 * {@code getPackagesToScan()} 은 그보다 더 뒤인 EntityManagerFactory <em>생성 시점</em>에 읽는다.
 */
public class MessagingEntityPackagesRegistrar implements ImportBeanDefinitionRegistrar {

    /** {@code OutboxEvent}·{@code ProcessedEvent} 가 사는 루트 패키지. */
    static final String MESSAGING_PACKAGE = "com.dawnline.messaging";

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        AutoConfigurationPackages.register(registry, MESSAGING_PACKAGE);
    }
}
