package com.dawnline.order.application.port.in;

import com.dawnline.common.error.ValidationException;
import com.dawnline.order.domain.OrderItem;
import com.dawnline.order.domain.Parcel;
import com.dawnline.order.domain.ServiceTier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 주문 접수 명령 (DESIGN.md §5.1 {@code POST /api/v1/orders}).
 *
 * <p>주문 id·접수 시각·약속 배송창·좌표는 <strong>여기에 없다</strong>. 넷 다 서버가 정한다 —
 * id 는 UUIDv7(불변규칙 10), 시각은 주입된 {@code Clock}(불변규칙 12), 약속창은 §2.2 표
 * ({@link com.dawnline.order.domain.DeliveryPromise}), 좌표는 {@code Geocoder} 다.
 * 호출자가 정하게 두면 그것은 약속도 접수 기록도 아니다.
 *
 * <h2>지문 ({@link #fingerprint()})</h2>
 * 같은 멱등 키로 <em>다른</em> 요청이 왔는지 판정하는 기준이다(§5.1 1단계 → 422).
 * 원문 바이트가 아니라 이 레코드의 표준형을 해싱한다 — 공백·필드 순서·널 생략만 다른 재전송이
 * 422 가 되면 안 되기 때문이다(ADR-018 §5).
 *
 * <p>그 대가는 분명하다. 컴포넌트를 늘리면서 {@link #canonicalForm()} 에 넣는 것을 잊으면
 * <strong>서로 다른 두 요청이 같은 지문을 갖는다</strong> — 두 번째 요청이 첫 번째의 응답을 받는다.
 * {@code PlaceOrderCommandTest} 가 레코드 컴포넌트 목록을 리플렉션으로 훑어 그것을 막는다.
 *
 * @param idempotencyKey {@code Idempotency-Key} 헤더 값. {@code idempotency_keys.idem_key} 는 VARCHAR(64)
 * @param customerId     고객 id. 무인증 API 이므로 클라이언트 주장값이다 (§10)
 * @param serviceTier    요청 티어
 * @param addressLine    배송지 주소 문자열. 로그·예외 상세에 넣지 않는다 (§9.3)
 * @param postalCode     우편번호. 좌표 조회 키다 (§5.1 {@code Geocoder})
 * @param parcel         소포 제원
 * @param items          품목. {@code lineNo} 는 어댑터가 1부터 매긴다 — 클라이언트가 정하지 않는다
 */
public record PlaceOrderCommand(
        String idempotencyKey,
        UUID customerId,
        ServiceTier serviceTier,
        String addressLine,
        String postalCode,
        Parcel parcel,
        List<OrderItem> items) {

    /** {@code idempotency_keys.idem_key} 의 컬럼 길이 (§5.1 DDL). */
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

    /** {@code orders.address_line} 과 {@code DeliveryAddress} 가 받는 길이. */
    private static final int MAX_ADDRESS_LINE_LENGTH = 200;

    /** {@code orders.postal_code} VARCHAR(10). */
    private static final int MAX_POSTAL_CODE_LENGTH = 10;

    /** 표준형의 필드 구분자(ASCII Unit Separator). 주소·SKU 에 나타날 수 없어 이어 붙이기가 모호해지지 않는다. */
    private static final char FIELD_SEPARATOR = 0x1F;

    /** 표준형의 품목 구분자(ASCII Record Separator). */
    private static final char ITEM_SEPARATOR = 0x1E;

    public PlaceOrderCommand {
        requireText(idempotencyKey, "idempotencyKey", MAX_IDEMPOTENCY_KEY_LENGTH);
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(serviceTier, "serviceTier");
        requireText(addressLine, "addressLine", MAX_ADDRESS_LINE_LENGTH);
        requireText(postalCode, "postalCode", MAX_POSTAL_CODE_LENGTH);
        Objects.requireNonNull(parcel, "parcel");
        Objects.requireNonNull(items, "items");
        items = List.copyOf(items);
        if (items.isEmpty()) {
            throw ValidationException.field("items", 0, "1개 이상이어야 합니다");
        }
    }

    /**
     * 요청의 표준형. 의미가 같은 요청은 같은 문자열이 된다.
     *
     * <p>레코드 컴포넌트를 <strong>전부</strong> 담아야 한다. 하나라도 빠지면 그 필드만 다른 두 요청이
     * 같은 지문을 갖는다.
     */
    public String canonicalForm() {
        StringBuilder canonical = new StringBuilder(256)
                .append(idempotencyKey).append(FIELD_SEPARATOR)
                .append(customerId).append(FIELD_SEPARATOR)
                .append(serviceTier).append(FIELD_SEPARATOR)
                .append(addressLine).append(FIELD_SEPARATOR)
                .append(postalCode).append(FIELD_SEPARATOR)
                .append(parcel.weightG()).append(',')
                .append(parcel.volumeCm3()).append(',')
                .append(parcel.requiresCold()).append(',')
                .append(parcel.hazmat()).append(FIELD_SEPARATOR);
        for (OrderItem item : items) {
            canonical.append(item.lineNo()).append(':')
                    .append(item.sku()).append(':')
                    .append(item.qty()).append(ITEM_SEPARATOR);
        }
        return canonical.toString();
    }

    /** 표준형의 SHA-256 을 소문자 16진수로. {@code idempotency_keys.request_hash} 는 CHAR(64) 다. */
    public String fingerprint() {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JRE 가 제공해야 하는 알고리즘이다(JCA 표준). 여기 오면 런타임이 깨진 것이다.
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
        }
        return HexFormat.of().formatHex(digest.digest(canonicalForm().getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 주소와 우편번호는 넣지 않는다 (CLAUDE.md 로그 규칙 — 전체 주소·고객 식별 정보 로그 금지).
     * 멱등 키도 넣지 않는다: 그 값을 알면 다른 사람의 접수 응답을 재생할 수 있다.
     */
    @Override
    public String toString() {
        return "PlaceOrderCommand[tier=" + serviceTier + ", items=" + items.size() + "]";
    }

    private static void requireText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw ValidationException.field(field, "", "비어 있을 수 없습니다");
        }
        if (value.length() > maxLength) {
            throw ValidationException.field(field, value.length(), maxLength + "자 이하여야 합니다");
        }
    }
}
