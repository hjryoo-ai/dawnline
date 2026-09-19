package com.dawnline.common.archunit.samples.good.adapter.in.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 규칙 8 검증용 정상 표본: 버전은 {@code {version}} 자리표시자로 두고 조건으로 건다 (ADR-009).
 *
 * <p>반대 방향이 없으면 「모든 매핑을 막는」 규칙이 되어도 테스트가 통과한다.
 */
@RestController
@RequestMapping(path = "/api/{version}/routes", version = "1")
public final class VersionPlaceholderController {

    /** 경로 변수 이름에 {@code v} 가 들어가도 세그먼트 전체가 {@code v숫자} 가 아니면 위반이 아니다. */
    @GetMapping("/{routeId}/stops/{v2Seq}")
    public String get(String routeId, String v2Seq) {
        return routeId + v2Seq;
    }
}
