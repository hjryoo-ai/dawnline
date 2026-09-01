package com.dawnline.common.error;

import java.io.Serial;
import java.util.Map;

/** 대상 리소스가 없다. HTTP 404. */
public class NotFoundException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    public NotFoundException(String message) {
        super(CommonErrorCode.NOT_FOUND, message);
    }

    public NotFoundException(String message, Map<String, Object> details) {
        super(CommonErrorCode.NOT_FOUND, message, details);
    }

    /**
     * 리소스 타입과 식별자로 만드는 표준 형태.
     *
     * @param resource 리소스 타입 이름 (예: {@code "Order"})
     * @param id       식별자
     */
    public static NotFoundException of(String resource, Object id) {
        return new NotFoundException(
                resource + " 를 찾을 수 없습니다: " + id,
                Map.of("resource", resource, "id", String.valueOf(id)));
    }
}
