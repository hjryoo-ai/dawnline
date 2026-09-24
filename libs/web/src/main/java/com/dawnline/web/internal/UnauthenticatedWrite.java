package com.dawnline.web.internal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 내부 토큰 없이 받는 쓰기 — <strong>고객·현장 표면</strong>에만 붙인다 (DESIGN.md §10, ADR-055 결정 1).
 *
 * <p>규칙은 기본 거부다. 이 표시가 없는 쓰기 핸들러는 토큰을 요구하므로, 열린 쪽으로 가려면 누군가 이것을
 * <em>적어야</em> 한다. 그리고 적은 것은 읽힌다 — 코어의 {@code OpenApiContractIT} 가 이 표시가 붙은 핸들러 집합을
 * 그 IT 의 면제 목록과 대조하므로, 새 핸들러에 붙이면 목록에도 적어야 초록이 된다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface UnauthenticatedWrite {

    /**
     * 왜 인증 없이 받는가. 비워 둘 수 없다 — 면제는 이유와 함께 읽혀야 한다.
     *
     * @return 이유 (예: 「고객 주문 API — §10 의 의도된 무인증」)
     */
    String reason();
}
