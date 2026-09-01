package com.dawnline.common.error;

import java.io.Serial;
import java.util.Map;

/** 요청 값이 유효하지 않다(형식·범위 위반). HTTP 400. */
public class ValidationException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ValidationException(String message) {
        super(CommonErrorCode.VALIDATION_FAILED, message);
    }

    public ValidationException(String message, Map<String, Object> details) {
        super(CommonErrorCode.VALIDATION_FAILED, message, details);
    }

    /**
     * 특정 필드 하나가 잘못된 경우의 표준 형태.
     *
     * @param field  잘못된 필드 이름 (예: {@code "lat"})
     * @param value  실제로 들어온 값 (JSON 직렬화 가능한 단순 값)
     * @param reason 사람이 읽는 이유
     */
    public static ValidationException field(String field, Object value, String reason) {
        return new ValidationException(
                field + ": " + reason,
                Map.of("field", field, "value", String.valueOf(value), "reason", reason));
    }
}
