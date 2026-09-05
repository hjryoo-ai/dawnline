package com.dawnline.dispatch.domain.optimizer.rule;

import com.dawnline.common.error.ValidationException;
import java.util.Map;
import java.util.Objects;

/**
 * 룰 정의 한 줄 (DESIGN.md §6.3, {@code dispatch_rules} 한 행 / 시드 JSON 한 원소).
 *
 * <h2>왜 JSON 이 아니라 Map 을 받는가</h2>
 * 이 패키지는 프레임워크 비의존이다(불변규칙 5) — Jackson 도 여기 오면 안 된다. 그래서 정의는
 * <strong>이미 파싱된</strong> 형태로 들어온다. 남는 중복은 "문자열을 Map 으로 읽는 한 줄" 뿐이고,
 * 그건 어댑터(JSONB)와 벤치마크 하네스(파일)가 각자 하는 것이 맞다 — 출처가 다르기 때문이다.
 *
 * @param name     룰 이름. {@code Explanation.ruleName} 으로 그대로 나간다
 * @param type     타입
 * @param severity 정의 파일이 적은 심각도. {@code type} 의 것과 같아야 한다
 * @param priority 평가 순서. 작을수록 먼저
 * @param params   타입별 파라미터
 */
public record RuleDefinition(String name, RuleType type, RuleSeverity severity, int priority,
        Map<String, Object> params) {

    public RuleDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(severity, "severity");
        params = Map.copyOf(Objects.requireNonNull(params, "params"));
        if (name.isBlank()) {
            throw ValidationException.field("name", name, "룰 이름은 비어 있을 수 없습니다");
        }
        if (severity != type.severity()) {
            // 정의가 자기 자신과 어긋났다. 조용히 지나가면 "HARD 로 적었는데 왜 배정됐지" 가 된다.
            throw ValidationException.field("severity", severity,
                    "%s 의 심각도는 %s 입니다".formatted(type, type.severity()));
        }
    }
}
