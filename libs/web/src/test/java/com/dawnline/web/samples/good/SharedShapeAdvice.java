package com.dawnline.web.samples.good;

import com.dawnline.web.ProblemDetailsAdviceSupport;
import java.util.Map;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 규칙 9 의 <strong>양성</strong> 표본 — 통과해야 할 것이 통과하는지 본다. */
@RestControllerAdvice
public class SharedShapeAdvice extends ProblemDetailsAdviceSupport {

    @Override
    protected Map<String, Integer> retryAfterSeconds() {
        return Map.of();
    }
}
