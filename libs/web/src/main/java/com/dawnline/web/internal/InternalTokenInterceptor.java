package com.dawnline.web.internal;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/**
 * 코어의 운영자 쓰기에 내부 토큰을 요구한다 (DESIGN.md §10 셋째 층, ADR-055 결정 2).
 *
 * <h2>필터가 아니라 인터셉터인 이유</h2>
 * <ul>
 *   <li><strong>면제는 핸들러 단위의 사실이다.</strong> 매핑이 끝난 핸들러를 받으므로 면제를 경로 문자열 목록이 아니라
 *       핸들러 메서드의 {@link UnauthenticatedWrite} 로 읽는다 — 경로 템플릿·버전 세그먼트와 대조하는 둘째 라우터가
 *       생기지 않는다.</li>
 *   <li><strong>매핑 뒤에 선다.</strong> 모르는 경로는 404, 지원하지 않는 버전은 400 으로 남는다(ADR-009).</li>
 * </ul>
 *
 * <h2>무엇을 통과시키나</h2>
 * 쓰기가 아닌 메서드, 면제 표시가 붙은 컨트롤러 메서드, 정적 자원 핸들러({@code GET}·{@code HEAD} 만 받는다 — 쓰기는
 * 405 로 남는다), 오류 디스패치(원래 요청이 이미 한 번 지났다). <strong>그 밖의 핸들러 타입은 토큰을 요구한다</strong> —
 * 이 저장소에 함수형 엔드포인트는 없지만 생기는 날 열린 쪽으로 새지 않는다.
 *
 * <p>거부는 응답을 쓰지 않고 {@link InternalTokenRejectedException} 을 던진다 — 서비스의 어드바이스가 Problem
 * Details 로 만든다. 비교는 상수 시간({@link MessageDigest#isEqual})이다.
 */
public final class InternalTokenInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(InternalTokenInterceptor.class);

    /** 토큰 대상 메서드. {@code GET}·{@code HEAD}·{@code OPTIONS}·{@code TRACE} 는 감사 대상이 아니다. */
    static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final byte[] expected;

    /**
     * @param properties 검증된 토큰 설정
     */
    public InternalTokenInterceptor(InternalTokenProperties properties) {
        this.expected = properties.secretBytes();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() != DispatcherType.REQUEST
                || !WRITE_METHODS.contains(request.getMethod())
                || handler instanceof ResourceHttpRequestHandler
                || handler instanceof HandlerMethod method && method.hasMethodAnnotation(UnauthenticatedWrite.class)) {
            return true;
        }
        String presented = request.getHeader(InternalToken.HEADER);
        if (presented != null && MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        // 값은 적지 않는다 — 틀린 토큰도 누군가의 진짜 토큰일 수 있다. 경로의 id 는 주소가 아니다(§9.3).
        log.warn("내부 토큰 없이 들어온 운영자 쓰기를 거부했습니다. method={} path={} reason={}",
                request.getMethod(), request.getRequestURI(), presented == null ? "missing" : "mismatch");
        throw new InternalTokenRejectedException();
    }
}
