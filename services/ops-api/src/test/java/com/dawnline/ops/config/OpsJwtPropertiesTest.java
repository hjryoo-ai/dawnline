package com.dawnline.ops.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 시크릿이 없거나 짧으면 ops-api 는 뜨지 않는다 (DESIGN.md §5.5). */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("OpsJwtProperties — 열린 채로 뜨는 것보다 뜨지 않는 것")
class OpsJwtPropertiesTest {

    @Test
    void 시크릿이_없으면_기동하지_않는다() {
        assertThatThrownBy(() -> new OpsJwtProperties(null)).hasMessageContaining("DAWNLINE_OPS_JWT_SECRET 가 없다");
        assertThatThrownBy(() -> new OpsJwtProperties("   ")).hasMessageContaining("DAWNLINE_OPS_JWT_SECRET 가 없다");
    }

    @Test
    void 바이트로_32보다_짧으면_기동하지_않는다() {
        assertThatThrownBy(() -> new OpsJwtProperties("0123456789abcdef0123456789abcde"))
                .hasMessageContaining("31바이트");
        // 한글 11자는 33바이트다 — 글자 수가 아니라 바이트로 잰다.
        assertThat(new OpsJwtProperties("가나다라마바사아자차카").secretBytes()).hasSize(33);
    }
}
