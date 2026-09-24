package com.dawnline.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 토큰이 없거나 짧으면 뜨지 않는다 (ADR-055 결정 2). */
class InternalTokenPropertiesTest {

    @Test
    void 없거나_비었으면_뜨지_않는다() {
        assertThatThrownBy(() -> new InternalTokenProperties(true, null))
                .hasMessageContaining("DAWNLINE_INTERNAL_TOKEN 이 없다");
        assertThatThrownBy(() -> new InternalTokenProperties(true, "   "))
                .hasMessageContaining("DAWNLINE_INTERNAL_TOKEN 이 없다");
    }

    @Test
    void 짧으면_뜨지_않는다() {
        assertThatThrownBy(() -> new InternalTokenProperties(true, "a".repeat(31)))
                .hasMessageContaining("31바이트");
    }

    @Test
    void 검사를_끈_서비스도_값을_검증한다() {
        // ops-api 는 검사를 끄지만 이 값을 코어 호출에 싣는다 — 빈 값으로 뜨면 모든 위임이 401 이다.
        assertThatThrownBy(() -> new InternalTokenProperties(false, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 경계는_32바이트를_포함한다() {
        assertThat(new InternalTokenProperties(true, "a".repeat(32)).secretBytes()).hasSize(32);
    }
}
