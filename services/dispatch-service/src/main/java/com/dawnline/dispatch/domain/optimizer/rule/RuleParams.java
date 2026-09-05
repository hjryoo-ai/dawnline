package com.dawnline.dispatch.domain.optimizer.rule;

import com.dawnline.common.error.ValidationException;
import java.util.List;
import java.util.Map;

/**
 * 룰 파라미터를 타입으로 읽는다.
 *
 * <p>파라미터는 JSONB 에서 오므로 무엇이든 들어올 수 있다. 잘못된 값은 <strong>계획 도중이 아니라
 * 룰을 만드는 시점에</strong> 실패해야 한다 — 계획이 절반쯤 돈 뒤에 터지면 어느 룰이 문제인지
 * 스택트레이스에서 찾아야 한다.
 */
final class RuleParams {

    private final String ruleName;
    private final Map<String, Object> params;

    RuleParams(String ruleName, Map<String, Object> params) {
        this.ruleName = ruleName;
        this.params = params;
    }

    long requireLong(String key) {
        Object value = require(key);
        if (!(value instanceof Number number)) {
            throw fail(key, value, "숫자여야 합니다");
        }
        return number.longValue();
    }

    int requireInt(String key) {
        long value = requireLong(key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw fail(key, value, "int 범위를 벗어났습니다");
        }
        return (int) value;
    }

    int requirePositiveInt(String key) {
        int value = requireInt(key);
        if (value <= 0) {
            throw fail(key, value, "양수여야 합니다");
        }
        return value;
    }

    String requireString(String key) {
        Object value = require(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw fail(key, value, "비어 있지 않은 문자열이어야 합니다");
        }
        return text;
    }

    List<String> requireStrings(String key) {
        Object value = require(key);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw fail(key, value, "비어 있지 않은 목록이어야 합니다");
        }
        return list.stream().map(element -> {
            if (!(element instanceof String text)) {
                throw fail(key, element, "목록의 원소는 문자열이어야 합니다");
            }
            return text;
        }).toList();
    }

    private Object require(String key) {
        Object value = params.get(key);
        if (value == null) {
            throw fail(key, null, "필수 파라미터입니다");
        }
        return value;
    }

    private ValidationException fail(String key, Object value, String message) {
        return ValidationException.field("%s.params.%s".formatted(ruleName, key), value, message);
    }
}
