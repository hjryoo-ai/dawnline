package com.dawnline.ops.config;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.Iterator;
import org.jspecify.annotations.Nullable;

/**
 * 레코드의 널 가능성을 문서의 {@code required} 로 옮긴다 (DESIGN.md §11 · CLAUDE.md 「널 가능성은 JSpecify」).
 *
 * <p>springdoc 은 {@code @NotNull} 같은 검증 어노테이션만 {@code required} 로 읽고 JSpecify 는 모른다. 그대로 두면
 * 응답 스키마의 모든 칸이 선택이 되고, 이 문서로 만든 ops-web 의 타입이 「{@code waveId} 가 없을 수 있다」고 말한다 —
 * 코드가 그렇게 말한 적이 없는데 문서가 약하게 말하는 것이다. 규칙은 하나다: <strong>레코드 컴포넌트의 타입에
 * {@code @Nullable} 이 없으면 필수</strong>. 원시 타입은 늘 필수다. 이미 필수인 칸(검증 어노테이션)은 그대로 둔다 —
 * 더할 뿐 빼지 않는다.
 *
 * <p>레코드만 본다: 이 서비스의 요청·응답 본문은 전부 레코드이고, 레코드가 아닌 타입(생성 클라이언트의 모델 등)에는
 * 이 규칙의 근거인 JSpecify 표시가 없다.
 */
final class NullabilityRequiredConverter implements ModelConverter {

    @Override
    @SuppressWarnings("rawtypes") // 인터페이스의 서명이 원시 타입이다(swagger-core)
    public @Nullable Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        if (!chain.hasNext()) {
            return null;
        }
        Schema<?> resolved = chain.next().resolve(type, context, chain);
        Class<?> raw = Json.mapper().constructType(type.getType()).getRawClass();
        if (resolved == null || !raw.isRecord()) {
            return resolved;
        }
        Schema<?> model = defined(resolved, context);
        if (model == null || model.getProperties() == null) {
            return resolved;
        }
        for (RecordComponent component : raw.getRecordComponents()) {
            boolean nullable = component.getAnnotatedType().isAnnotationPresent(Nullable.class);
            if (!nullable && model.getProperties().containsKey(component.getName())
                    && (model.getRequired() == null || !model.getRequired().contains(component.getName()))) {
                model.addRequiredItem(component.getName());
            }
        }
        return resolved;
    }

    /** 참조로 돌아왔으면 정의된 모델을, 아니면 그 자신을. */
    private static @Nullable Schema<?> defined(Schema<?> resolved, ModelConverterContext context) {
        String ref = resolved.get$ref();
        if (ref == null) {
            return resolved;
        }
        return context.getDefinedModels().get(ref.substring(ref.lastIndexOf('/') + 1));
    }
}
