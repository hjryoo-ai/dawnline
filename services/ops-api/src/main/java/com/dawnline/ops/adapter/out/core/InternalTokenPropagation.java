package com.dawnline.ops.adapter.out.core;

import com.dawnline.web.internal.InternalToken;
import com.dawnline.web.internal.InternalTokenProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * 모든 코어 호출에 내부 토큰을 싣는다 (DESIGN.md §5.5 「커맨드 위임」, §10 셋째 층, ADR-055 결정 4).
 *
 * <p>호출마다 고르지 않는다 — 면제 경로(주문 취소)에도 싣는다. 「어느 호출에 싣는가」를 호출마다 고르는 규칙은 새는
 * 규칙이고, 새면 그 커맨드는 머지 직후 401 이다. 생성된 인터페이스에 헤더 파라미터를 끼우지 않는 것은
 * {@link AuditIdPropagation} 과 같은 이유다(ADR-052 기준 2).
 */
public final class InternalTokenPropagation implements ClientHttpRequestInterceptor {

    private final String token;

    /**
     * @param properties 검증된 토큰 설정 — ops-api 는 검사를 끄지만 값은 검증한다
     */
    public InternalTokenPropagation(InternalTokenProperties properties) {
        this.token = new String(properties.secretBytes(), StandardCharsets.UTF_8);
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        request.getHeaders().set(InternalToken.HEADER, token);
        return execution.execute(request, body);
    }
}
