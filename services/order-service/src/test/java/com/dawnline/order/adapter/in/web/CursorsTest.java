package com.dawnline.order.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Ids;
import com.dawnline.common.error.ValidationException;
import com.dawnline.order.application.port.in.OrderCursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 커서 인코딩 (DESIGN.md §5.1). */
@DisplayName("Cursors — 불투명 커서")
class CursorsTest {

    private static final Instant PLACED_AT = Instant.parse("2026-09-03T00:00:00.123456Z");

    @Test
    void 왕복해도_같은_값이다() {
        OrderCursor cursor = new OrderCursor(PLACED_AT, Ids.newId());

        assertThat(Cursors.decode(Cursors.encode(cursor))).isEqualTo(cursor);
    }

    @Test
    void 마이크로초까지_보존한다() {
        // 커서가 초 단위로 잘리면 같은 초에 접수된 주문들이 매 페이지마다 다시 나온다.
        OrderCursor cursor = new OrderCursor(PLACED_AT, Ids.newId());

        assertThat(Cursors.decode(Cursors.encode(cursor)).placedAt()).isEqualTo(PLACED_AT);
    }

    @Test
    void URL_에_그대로_넣을_수_있다() {
        // 표준 base64 의 + / = 는 쿼리 파라미터에서 깨진다.
        String encoded = Cursors.encode(new OrderCursor(PLACED_AT, Ids.newId()));

        assertThat(encoded).matches("^[A-Za-z0-9_-]+$");
    }

    @Test
    void 값이_다르면_커서도_다르다() {
        UUID id = Ids.newId();

        assertThat(Cursors.encode(new OrderCursor(PLACED_AT, id)))
                .isNotEqualTo(Cursors.encode(new OrderCursor(PLACED_AT.plusNanos(1000), id)))
                .isNotEqualTo(Cursors.encode(new OrderCursor(PLACED_AT, Ids.newId())));
    }

    @Test
    void base64_가_아니면_400_이다() {
        assertThatThrownBy(() -> Cursors.decode("!!!not-base64!!!"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cursor");
    }

    @Test
    void 구분자가_없으면_400_이다() {
        String noSeparator = encodeRaw("2026-09-03T00:00:00Z");

        assertThatThrownBy(() -> Cursors.decode(noSeparator)).isInstanceOf(ValidationException.class);
    }

    @Test
    void 시각이나_id_가_망가져도_400_이다() {
        assertThatThrownBy(() -> Cursors.decode(encodeRaw("어제|" + Ids.newId())))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Cursors.decode(encodeRaw("2026-09-03T00:00:00Z|주문1")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void 오류_상세에_해독한_내용을_담지_않는다() {
        // 위조된 값을 그대로 되돌려 주면 반사 공격의 통로가 된다.
        String forged = encodeRaw("<script>alert(1)</script>|x");

        assertThatThrownBy(() -> Cursors.decode(forged))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).details().toString())
                        .doesNotContain("script"));
    }

    @Test
    void null_은_거부한다() {
        assertThatThrownBy(() -> Cursors.encode(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Cursors.decode(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void 유틸리티_클래스는_생성할_수_없다() throws NoSuchMethodException {
        var constructor = Cursors.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance)
                .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
                .hasCauseInstanceOf(AssertionError.class);
    }

    private static String encodeRaw(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
