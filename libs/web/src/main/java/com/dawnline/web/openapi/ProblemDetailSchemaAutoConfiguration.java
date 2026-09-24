package com.dawnline.web.openapi;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * {@link ProblemDetailSchema} 를 springdoc 을 쓰는 모든 서비스에 건다.
 *
 * <p>서비스마다 빈을 적게 하지 않는 이유: 넷째 서비스가 그 한 줄을 잊으면 그 서비스의 문서만 다시 거짓을
 * 말하고, 그것은 조용하다. 세 서비스의 {@code OpenApiContractIT} 가 결과를 확인하지만 확인은 <em>있는</em>
 * 서비스만 본다.
 */
@AutoConfiguration
@ConditionalOnClass(OpenApiCustomizer.class)
public class ProblemDetailSchemaAutoConfiguration {

    /** @return 문서의 {@code ProblemDetail} 을 실제 본문의 모양으로 고치는 customizer */
    @Bean
    public OpenApiCustomizer problemDetailSchema() {
        return new ProblemDetailSchema();
    }
}
