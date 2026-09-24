package com.dawnline.web.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** springdoc 이 그리는 모양을 실제 본문의 모양으로 — 확장 멤버는 최상위다. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ProblemDetailSchema — 문서의 확장 칸을 최상위로")
class ProblemDetailSchemaTest {

    @Test
    void 중첩_properties_를_지우고_code_를_최상위에_두고_추가_칸을_허용한다() {
        OpenAPI openApi = new OpenAPI().components(new Components().addSchemas(ProblemDetailSchema.NAME,
                new ObjectSchema()
                        .addProperty("type", new StringSchema().format("uri"))
                        .addProperty("status", new IntegerSchema())
                        .addProperty("properties", new ObjectSchema().additionalProperties(new Schema<>()))));

        new ProblemDetailSchema().customise(openApi);

        Schema<?> problem = openApi.getComponents().getSchemas().get(ProblemDetailSchema.NAME);
        List<String> names = List.copyOf(problem.getProperties().keySet());
        assertThat(problem.getAdditionalProperties()).isEqualTo(Boolean.TRUE);
        assertThat(names).as("RFC 9457 의 칸이 앞이고 중첩 properties 는 없다")
                .containsExactly("type", "status", "code");
    }

    @Test
    void ProblemDetail_이_없는_문서는_건드리지_않는다() {
        OpenAPI openApi = new OpenAPI().components(new Components().addSchemas("OrderView", new ObjectSchema()));

        new ProblemDetailSchema().customise(openApi);
        new ProblemDetailSchema().customise(new OpenAPI());

        assertThat(openApi.getComponents().getSchemas()).containsOnlyKeys("OrderView");
    }
}
