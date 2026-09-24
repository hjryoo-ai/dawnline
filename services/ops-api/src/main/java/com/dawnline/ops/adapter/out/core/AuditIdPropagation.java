package com.dawnline.ops.adapter.out.core;

import com.dawnline.observability.MdcKeys;
import java.io.IOException;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * 감사 행 id 를 코어 호출의 상관 헤더로 싣는다 (DESIGN.md §5.5 「커맨드 위임」, §9.3).
 *
 * <p>생성된 인터페이스에는 헤더 파라미터가 없다 — 계약 문서에 없는 것을 생성물에 끼워 넣지 않는다(ADR-052
 * 기준 2). 그래서 id 는 호출 하나의 범위에 묶인 {@link ScopedValue} 로 인터셉터까지 간다. 동기 호출이라
 * 같은 스레드이고, 범위를 벗어나면 값이 없다 — 위임이 아닌 호출에 남의 감사 id 가 실릴 수 없다.
 */
public final class AuditIdPropagation implements ClientHttpRequestInterceptor {

    private static final ScopedValue<UUID> AUDIT_ID = ScopedValue.newInstance();

    /**
     * {@code auditId} 를 실은 채로 {@code call} 을 부른다.
     *
     * @param auditId 감사 행 id
     * @param call    코어 호출
     * @param <T>     결과
     * @return {@code call} 의 결과
     */
    static <T> T with(UUID auditId, Supplier<T> call) {
        return ScopedValue.where(AUDIT_ID, auditId).call(call::get);
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (AUDIT_ID.isBound()) {
            request.getHeaders().set(MdcKeys.AUDIT_ID_HEADER, AUDIT_ID.get().toString());
        }
        return execution.execute(request, body);
    }
}
