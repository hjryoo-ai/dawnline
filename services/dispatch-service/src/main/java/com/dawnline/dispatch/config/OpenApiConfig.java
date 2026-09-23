package com.dawnline.dispatch.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 문서 (DESIGN.md §5.3, §14 — {@code contracts/openapi/dispatch-service.yaml}).
 *
 * <p>생성된 문서는 저장소에 커밋되고 {@code OpenApiContractIT} 가 코드와 어긋나지 않는지
 * 검사한다. 문서를 손으로 고치는 순간 그 테스트가 깨진다.
 *
 * <p><strong>왜 Phase 6 인가</strong>: springdoc 은 Phase 3 부터 붙어 있었지만 생성물이 없었다.
 * 그것은 문서가 <em>거짓을 말하는</em> 상태가 아니라 <em>없는</em> 상태이고, 부재는 첫 소비자가
 * 나타나는 시점에 채우는 것이 소비자 주도 원칙과 맞는다(§11). 그 소비자가 ops-api 다 — 이
 * 문서로 코어 위임 클라이언트를 만든다(불변규칙 4: 동기 호출은 ops-api → 코어 방향만).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    /** 문서에 적히는 API 버전. 경로 세그먼트 {@code v1} 과 같은 major 다 (ADR-009). */
    private static final String API_VERSION = "1.0.0";

    /** 로컬 기본 포트 (application.yml 의 {@code server.port}). */
    private static final String LOCAL_SERVER = "http://localhost:8083";

    /** springdoc 이 {@code {version}} 자리표시자를 채운 결과. */
    private static final String RESOLVED_VERSION_PREFIX = "/api/1/";

    /** 설계서(§5.3)가 쓰는 정식 형태. */
    private static final String CANONICAL_VERSION_PREFIX = "/api/v1/";

    /** OpenAPI 문서의 머리말. */
    @Bean
    public OpenAPI dispatchServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Dawnline dispatch-service")
                        .version(API_VERSION)
                        .description("""
                                운영자 배차 API (DESIGN.md §5.3). 계획 실행·조회, 라우트 조회, \
                                stop 재배정, 자원·룰 관리.

                                **고객 API 가 아니다.** 이 표면은 ops-api 를 통해서만 노출된다 \
                                (불변규칙 4 — 코어 서비스 간 동기 호출은 금지이고 ops-api → 코어 방향만 있다). \
                                그래서 §7.2 의 고객 레이트 리밋이 붙지 않는다.

                                **계획 실행은 멱등이다.** 같은 웨이브에 다시 돌려도 답이 같다 \
                                (§5.3 `wave_id` UNIQUE). 그래서 `Retry-After` 를 쓰는 오류가 없다 — \
                                재시도가 통하는 오류와 통하지 않는 오류를 나눌 필요가 아직 없다.

                                **재배정은 두 라우트를 모두 바꾼다.** 옮긴 쪽과 받은 쪽 둘 다 \
                                `revision` 이 오르고 `route.assigned` 가 다시 나간다. 소비자는 자신이 \
                                이미 본 revision 이하를 무시하므로(§6.8 4단계) 순서가 뒤바뀌어도 안전하다.

                                오류 응답은 RFC 9457 Problem Details 이고 `type` 과 `code` 가 항상 채워진다."""))
                .servers(List.of(new Server().url(LOCAL_SERVER).description("로컬 개발")))
                .components(new Components());
    }

    /**
     * 경로의 버전 자리표시자를 <strong>호출자가 실제로 부르는 형태</strong>로 되돌린다.
     *
     * <p>컨트롤러 매핑은 {@code /api/{version}/...} 다(ADR-009). springdoc 은 그 자리를
     * <em>해석된 버전 값</em>으로 채워 {@code /api/1/...} 을 적는다. 그 주소로도 요청은 통하지만
     * 설계서(§5.3)가 쓰는 정식 주소가 아니고, 문서를 보고 만든 클라이언트가 우리가 지원한다고
     * 말한 적 없는 형태를 쓰게 된다 — 그 클라이언트가 ops-api 다.
     */
    @Bean
    public OpenApiCustomizer canonicalVersionPathCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            Paths canonical = new Paths();
            canonical.extensions(openApi.getPaths().getExtensions());
            openApi.getPaths().forEach((path, item) ->
                    canonical.addPathItem(path.replace(RESOLVED_VERSION_PREFIX, CANONICAL_VERSION_PREFIX), item));
            openApi.setPaths(canonical);
        };
    }
}
