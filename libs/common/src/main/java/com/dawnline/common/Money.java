package com.dawnline.common;

/**
 * KRW 정수 금액.
 *
 * <p>CLAUDE.md 불변규칙 9: 돈은 정수 KRW 로만 다룬다. 부동소수 금액은 금지다.
 * 원 단위 아래가 없는 통화이므로 {@code long} 하나로 충분하며,
 * 모든 산술은 {@link Math#addExact(long, long)} 계열로 오버플로 시 즉시 실패한다.
 *
 * @param krw 원 단위 금액 (음수 허용 — 할인·보정에 쓴다)
 */
public record Money(long krw) implements Comparable<Money> {

    /** 0원. */
    public static final Money ZERO = new Money(0L);

    /** {@code new Money(krw)} 의 읽기 쉬운 별칭. */
    public static Money krw(long krw) {
        return krw == 0L ? ZERO : new Money(krw);
    }

    /** 덧셈. 오버플로 시 {@link ArithmeticException}. */
    public Money plus(Money other) {
        return krw(Math.addExact(this.krw, other.krw));
    }

    /** 뺄셈. 오버플로 시 {@link ArithmeticException}. */
    public Money minus(Money other) {
        return krw(Math.subtractExact(this.krw, other.krw));
    }

    /** 정수배. 오버플로 시 {@link ArithmeticException}. */
    public Money multipliedBy(long factor) {
        return krw(Math.multiplyExact(this.krw, factor));
    }

    /** 부호 반전. */
    public Money negated() {
        return krw(Math.negateExact(this.krw));
    }

    /** 0원인가. */
    public boolean isZero() {
        return krw == 0L;
    }

    /** 음수인가. */
    public boolean isNegative() {
        return krw < 0L;
    }

    /** 양수인가. */
    public boolean isPositive() {
        return krw > 0L;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(this.krw, other.krw);
    }

    @Override
    public String toString() {
        return krw + " KRW";
    }
}
