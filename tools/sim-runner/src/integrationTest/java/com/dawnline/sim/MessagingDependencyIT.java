package com.dawnline.sim;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@link MessagingDependencyTest} 와 <strong>같은 어설션을 다른 클래스패스에서</strong> 돌린다.
 *
 * <p>JPA 가 새어 들어온 자리가 바로 여기였다 — {@code integrationTest} 는 계약 픽스처
 * ({@code testFixtures(project(":libs:messaging"))}) 를 쓰느라 같은 프로젝트를 한 번 더
 * 선언하고, Gradle 의 {@code exclude} 는 선언마다 걸린다. 단위 테스트만 있던 동안 그 누수는
 * <em>초록</em>이었고 {@code SimDriverIT} 이 컨텍스트를 띄우다 죽고 나서야 드러났다.
 *
 * <p>컨테이너를 띄우지 않는다(클래스 로딩뿐). {@code integrationTest} 소스셋에 두는 이유는
 * 검증 대상이 <strong>이 소스셋의 클래스패스</strong>이기 때문이다 —
 * {@code ImageTagsMatchComposeIT} 가 자기 소스셋의 상수를 보는 것과 같은 자리다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MessagingDependencyIT {

    @Test
    void JPA_는_integrationTest_클래스패스에도_없다() {
        MessagingDependencyTest.assertJpaAbsent();
    }
}
