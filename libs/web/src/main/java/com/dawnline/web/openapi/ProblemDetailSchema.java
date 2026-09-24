package com.dawnline.web.openapi;

import com.dawnline.web.ProblemDetailsAdviceSupport;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * 문서의 {@code ProblemDetail} 스키마를 <strong>실제 본문의 모양</strong>으로 고친다 (DESIGN.md §11,
 * ADR-052 「문서의 거짓 하나」).
 *
 * <h2>무엇이 거짓이었나</h2>
 * springdoc 은 {@link org.springframework.http.ProblemDetail} 의 {@code getProperties()} 맵을 보고 확장
 * 멤버를 {@code properties} 라는 <em>중첩 객체</em>로 그린다. 실제 본문은 Spring 의 Jackson 믹스인이 그
 * 맵을 <strong>최상위로 펼친다</strong> — {@code "code": "not-found"} 는 {@code type} 옆에 온다(tracking 의
 * {@code ScanApiIT} 가 {@code jsonPath("$.code")} 로 본다). 문서대로 만든 클라이언트는 {@code code} 를
 * 잃는다. 그 클라이언트가 실제로 있었다 — ops-api 의 위임 클라이언트가 이 문서에서 생성된다.
 *
 * <h2>고친 모양</h2>
 * 중첩 {@code properties} 를 지우고, {@link ProblemDetailsAdviceSupport} 가 모든 오류에 싣는 {@code code}
 * 를 최상위 칸으로 적고, 그 밖의 확장 멤버({@code retryAfterSeconds}·검증 오류 목록·도메인 예외의 상세)를
 * 위해 추가 칸을 허용한다. 생성기의 오류는 생성기 쪽에서 고친다 — 문서를 손으로 고치면
 * {@code OpenApiContractIT} 가 깨진다.
 *
 * <p>등록은 {@link ProblemDetailSchemaAutoConfiguration} 이 한다 — springdoc 을 쓰는 서비스는 전부 받는다.
 * 경로의 {@code /api/1} 을 정식 형태로 되돌리는 customizer 와 같은 자리의 일이다.
 */
public final class ProblemDetailSchema implements OpenApiCustomizer {

    /** 문서의 스키마 이름 — springdoc 이 {@code org.springframework.http.ProblemDetail} 에 붙이는 이름. */
    public static final String NAME = "ProblemDetail";

    /** springdoc 이 확장 멤버 맵을 그리는 중첩 칸의 이름. */
    static final String NESTED_EXTENSIONS = "properties";

    /** 모든 오류에 실리는 확장 멤버. */
    static final String CODE = "code";

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        Schema problem = openApi.getComponents().getSchemas().get(NAME);
        if (problem == null || problem.getProperties() == null) {
            return;
        }
        Map<String, Schema> flattened = new LinkedHashMap<>(problem.getProperties());
        flattened.remove(NESTED_EXTENSIONS);
        flattened.put(CODE, new StringSchema().description(
                "오류 코드 — `type` 의 마지막 세그먼트와 같다. 모든 오류에 실린다. 그 밖의 확장 멤버"
                        + "(`retryAfterSeconds`, 검증 오류, 도메인 예외의 상세)도 이 칸처럼 최상위에 온다"));
        problem.setProperties(flattened);
        problem.setAdditionalProperties(Boolean.TRUE);
    }
}
