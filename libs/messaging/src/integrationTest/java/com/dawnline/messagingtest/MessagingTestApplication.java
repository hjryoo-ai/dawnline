package com.dawnline.messagingtest;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 통합 테스트용 부트 애플리케이션.
 *
 * <p>패키지가 {@code com.dawnline.messaging} 이 <strong>아닌</strong> 것이 의도다.
 * 그래야 {@code com.dawnline.messaging} 의 JPA 엔티티가 애플리케이션 기본 스캔 범위 밖에 놓이고,
 * {@code MessagingEntityPackagesRegistrar} 가 실제로 동작하는지 검증된다.
 * 같은 패키지에 두면 등록자가 고장 나도 테스트가 통과해 버린다.
 */
@SpringBootApplication
public class MessagingTestApplication {

    /** Spring 이 인스턴스를 만든다. */
    public MessagingTestApplication() {
    }
}
