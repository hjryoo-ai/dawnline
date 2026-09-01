package com.dawnline.common.error;

import java.io.Serial;
import java.util.Map;

/** 현재 상태와 충돌해 요청을 수행할 수 없다. HTTP 409. */
public class ConflictException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(CommonErrorCode.CONFLICT, message);
    }

    public ConflictException(String message, Map<String, Object> details) {
        super(CommonErrorCode.CONFLICT, message, details);
    }
}
