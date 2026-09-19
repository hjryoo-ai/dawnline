package com.dawnline.common.archunit.samples.bad.adapter.in.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 규칙 8 검증용 위반 표본: 매핑 경로에 버전이 박혀 있다 (ADR-009 결정 2 위반).
 *
 * <p>이렇게 두면 {@code /api/v2/routes} 는 경로 매칭에서 먼저 떨어져 <strong>404</strong> 가
 * 되고, 클라이언트는 「그런 리소스가 없다」와 「그 버전은 지원하지 않는다」를 구분할 수 없다.
 * 올바른 형태는 {@code @RequestMapping(path = "/api/{version}/routes", version = "1")} 이다.
 */
@RestController
@RequestMapping("/api/v1/routes")
public final class VersionedPathController {

    /** 메서드 쪽 매핑에는 버전이 없다 — 위반은 클래스 매핑 하나다. */
    @GetMapping("/{routeId}")
    public String get(String routeId) {
        return routeId;
    }
}
