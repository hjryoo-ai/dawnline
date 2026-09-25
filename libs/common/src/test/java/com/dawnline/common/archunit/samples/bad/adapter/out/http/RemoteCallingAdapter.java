package com.dawnline.common.archunit.samples.bad.adapter.out.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import org.springframework.web.client.RestClient;

/**
 * 규칙 12 위반 표본 — 코어의 어댑터가 HTTP 로 다른 곳을 부른다(불변규칙 4, ADR-015 후속 정정).
 *
 * <p>둘 다 컴파일되고 동작한다. 그리고 둘 다 조용히 두 문장을 깬다: 코어 사이의 동기 호출(규칙 3 은 패키지 참조만 잡는다)과,
 * 소비 측 경계표의 「HTTP — 해당 없음」 — 이 호출이 리스너 아래에서 실패하면 그 예외는 표의 어느 행에도 걸리지 않고 「그 밖」으로
 * 떨어진다. 그 판정이 맞는지 아무도 묻지 않은 채로.
 */
public class RemoteCallingAdapter {

    private final RestClient rest = RestClient.create("http://dispatch-service:8080");

    private final HttpClient jdk = HttpClient.newHttpClient();

    /**
     * @return 요청 — 보내지 않는다. 의존만 있으면 규칙이 잡는다
     */
    public HttpRequest request() {
        rest.get();
        return HttpRequest.newBuilder(URI.create("http://tracking-service:8080")).build();
    }

    /**
     * @return JDK 클라이언트
     */
    public HttpClient jdk() {
        return jdk;
    }
}
