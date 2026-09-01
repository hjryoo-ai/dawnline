package com.dawnline.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Money — KRW 정수 금액")
class MoneyTest {

    @Test
    void ZERO_는_0원이고_krw_팩토리와_같다() {
        assertThat(Money.ZERO.krw()).isZero();
        assertThat(Money.krw(0L)).isSameAs(Money.ZERO);
        assertThat(Money.krw(3_500L)).isEqualTo(new Money(3_500L));
    }

    @ParameterizedTest(name = "{0} + {1} = {2}")
    @CsvSource({"1000, 2000, 3000", "-500, 500, 0", "0, 0, 0", "-100, -200, -300"})
    void plus_는_원_단위로_더한다(long left, long right, long expected) {
        assertThat(Money.krw(left).plus(Money.krw(right))).isEqualTo(Money.krw(expected));
    }

    @ParameterizedTest(name = "{0} - {1} = {2}")
    @CsvSource({"3000, 1000, 2000", "1000, 3000, -2000", "0, 0, 0"})
    void minus_는_원_단위로_뺀다(long left, long right, long expected) {
        assertThat(Money.krw(left).minus(Money.krw(right))).isEqualTo(Money.krw(expected));
    }

    @ParameterizedTest(name = "{0} × {1} = {2}")
    @CsvSource({"1200, 3, 3600", "1200, 0, 0", "1200, -2, -2400"})
    void multipliedBy_는_정수배한다(long amount, long factor, long expected) {
        assertThat(Money.krw(amount).multipliedBy(factor)).isEqualTo(Money.krw(expected));
    }

    @Test
    void negated_는_부호를_뒤집는다() {
        assertThat(Money.krw(1_500L).negated()).isEqualTo(Money.krw(-1_500L));
        assertThat(Money.ZERO.negated()).isEqualTo(Money.ZERO);
    }

    @Test
    void 오버플로는_조용히_감싸지_않고_예외로_터진다() {
        Money max = Money.krw(Long.MAX_VALUE);
        Money min = Money.krw(Long.MIN_VALUE);

        assertThatThrownBy(() -> max.plus(Money.krw(1L))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> min.minus(Money.krw(1L))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> max.multipliedBy(2L)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(min::negated).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void 부호_판별_메서드들() {
        assertThat(Money.ZERO.isZero()).isTrue();
        assertThat(Money.ZERO.isNegative()).isFalse();
        assertThat(Money.ZERO.isPositive()).isFalse();

        assertThat(Money.krw(-1L).isNegative()).isTrue();
        assertThat(Money.krw(-1L).isZero()).isFalse();
        assertThat(Money.krw(-1L).isPositive()).isFalse();

        assertThat(Money.krw(1L).isPositive()).isTrue();
        assertThat(Money.krw(1L).isNegative()).isFalse();
    }

    @Test
    void compareTo_로_정렬할_수_있다() {
        List<Money> sorted = List.of(Money.krw(300L), Money.krw(-100L), Money.ZERO, Money.krw(50L))
                .stream()
                .sorted()
                .toList();

        assertThat(sorted)
                .containsExactly(Money.krw(-100L), Money.ZERO, Money.krw(50L), Money.krw(300L));
        assertThat(Money.krw(10L)).isGreaterThan(Money.krw(9L)).isLessThan(Money.krw(11L));
        assertThat(Money.krw(10L)).isEqualByComparingTo(Money.krw(10L));
    }

    @Test
    void toString_은_원_단위를_그대로_보여준다() {
        assertThat(Money.krw(12_000L)).hasToString("12000 KRW");
        assertThat(Money.krw(-1L)).hasToString("-1 KRW");
    }
}
