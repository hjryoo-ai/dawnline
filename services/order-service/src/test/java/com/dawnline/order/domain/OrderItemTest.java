package com.dawnline.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("OrderItem — 주문 품목 (DESIGN.md §5.1)")
class OrderItemTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 수량이_0_이하면_거부한다(int qty) {
        // DDL 의 CHECK (qty > 0) 와 같은 규칙이다.
        assertThatThrownBy(() -> new OrderItem((short) 1, "SKU-1", qty))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("qty");
    }

    @ParameterizedTest
    @ValueSource(shorts = {0, -1})
    void 순번이_1_미만이면_거부한다(short lineNo) {
        assertThatThrownBy(() -> new OrderItem(lineNo, "SKU-1", 1))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("lineNo");
    }

    @Test
    void SKU_가_비어_있으면_거부한다() {
        assertThatThrownBy(() -> new OrderItem((short) 1, "  ", 1))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("sku");
    }

    @Test
    void 지나치게_긴_SKU_는_거부한다() {
        // §10 입력 검증: SKU 길이 제한.
        assertThatThrownBy(() -> new OrderItem((short) 1, "S".repeat(33), 1))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("sku");
    }

    @Test
    void SKU_앞뒤_공백은_다듬는다() {
        assertThat(new OrderItem((short) 1, "  SKU-1  ", 1).sku()).isEqualTo("SKU-1");
    }
}
