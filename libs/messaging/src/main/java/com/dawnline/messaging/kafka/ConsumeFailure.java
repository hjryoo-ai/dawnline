package com.dawnline.messaging.kafka;

import com.dawnline.common.error.DomainException;
import com.dawnline.messaging.FailureKind;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.invocation.MethodArgumentResolutionException;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.core.JacksonException;

/**
 * 소비 측 경계표 — 리스너 실패가 일시적인가 결정적인가 (DESIGN.md §4.6 「경계」, ADR-015 후속 정정 2026-09-25).
 *
 * <p><strong>이 enum 이 표의 정본이다.</strong> ADR-015 의 표는 복사이고 {@code ConsumeFailureTableTest} 가 행 이름 · 판정 · 순서를
 * 대조한다. {@link #reason()} 은 {@code dawnline_event_retry_total} 의 {@code reason} 라벨 값이고, 카탈로그의 닫힌 값과 대조된다.
 *
 * <h2>판정 — 위에서부터, 원인 사슬 어딘가에 걸리는 첫 행</h2>
 * 상수의 선언 순서가 판정 순서다. 원인 사슬 전체를 보므로 {@code ListenerExecutionFailedException} 같은 겉옷은 판정에 영향이 없다.
 * <strong>일시적 행이 결정적 행보다 위에 선다</strong> — 기본값이 이미 일시적인데도 행이 있는 이유다. DB 연결 실패가 도메인 예외에
 * 싸여 올라와도 일시적이다. 장애가 DLQ 로 흐르지 않는다. 그리고 카운터의 {@code reason} 이 무엇 때문에 멈췄는지를 행 이름으로 말한다.
 *
 * <h2>애매하면 일시적</h2>
 * 걸리는 행이 없으면 {@link #OTHER} — 일시적이다. 소비 측에서 틀린 쪽의 대가가 비대칭이기 때문이다. 결정적인데 일시적이라 하면
 * 파티션이 서고 알림이 울린다(보이는 실패). 일시적인데 결정적이라 하면 이벤트가 조용히 DLQ 로 빠진다(찾아야 하는 실패).
 *
 * <h2>HTTP 행이 없는 이유</h2>
 * ADR 의 표에는 「HTTP — 해당 없음」 행이 있고 여기에는 없다. 코어의 리스너는 외부 HTTP 를 부르지 않는다 — ArchUnit 규칙 12 가 그것을
 * 지킨다. 그 규칙이 깨지는 날 이 enum 에 행이 생긴다.
 */
public enum ConsumeFailure {

    /**
     * 역직렬화 · 스키마 불일치 · 리스너를 부를 수 없음 — <strong>즉시 DLQ</strong>(재시도 0).
     *
     * <p>이 판정을 실제로 내리는 것은 Spring Kafka 의 재시도 제외 목록이다({@link DawnlineErrorHandlers}). 그 목록은 이 행보다
     * 먼저 돈다 — 그래서 이 행이 표의 맨 위다. 여기의 타입은 그 목록과 같고, {@code ConsumeFailureTest} 가 Spring 의 기본
     * 치명 목록과 대조한다. 재시도 경로에 들지 않으므로 {@code dawnline_event_retry_total} 의 값이 아니다.
     */
    DESERIALIZATION(FailureKind.DETERMINISTIC, anyOf(Types.IMMEDIATE_DLQ)),

    /**
     * Redis — 연결 실패, 명령 시간 초과. 대부분 여기까지 오지 않는다 — 코어의 Redis 호출은 폴백이 먼저 잡는다(§7.2).
     *
     * <p>타입이 아니라 <strong>패키지</strong>로 본다. 이 라이브러리는 Redis 를 참조하지 않고(릴레이 락이 advisory lock 으로 옮긴 뒤),
     * 명령 시간 초과는 Spring 의 {@code QueryTimeoutException} 으로 번역돼 아래 DB 행에 먼저 걸릴 수 있다 — 그 사슬의 안쪽에는
     * Lettuce 예외가 있다. DB 행들보다 위인 이유: {@code RedisConnectionFailureException} 은
     * {@link DataAccessResourceFailureException} 이다.
     */
    REDIS(FailureKind.TRANSIENT, failure -> {
        String name = failure.getClass().getName();
        return name.startsWith("org.springframework.data.redis.") || name.startsWith("io.lettuce.");
    }),

    /** 커넥션을 얻지 못했다 — RB-02 재현의 Hikari 30초 대기가 이 행이다. */
    DB_CONNECTION(FailureKind.TRANSIENT, anyOf(CannotCreateTransactionException.class,
            CannotGetJdbcConnectionException.class, SQLTransientConnectionException.class)),

    /** 자원 장애 — 세션이 끊겼다, 서버가 내려갔다. */
    DB_RESOURCE(FailureKind.TRANSIENT, anyOf(DataAccessResourceFailureException.class, SQLRecoverableException.class)),

    /** 일시적 DB 오류 — 쿼리 시간 초과 · 락 대기 · 교착 · 낙관적 락 충돌. */
    DB_TRANSIENT(FailureKind.TRANSIENT, anyOf(TransientDataAccessException.class, SQLTransientException.class)),

    /**
     * 무결성 위반 — <strong>애매한 행이다.</strong> 제약 결함이면 매번 같은 결과(결정적)이고, 두 인스턴스의 경합이면 재시도에서
     * 멱등 검사가 통과해 풀린다(일시적). 애매하면 일시적이다. 결함이면 파티션이 서고 재시도 나이의 알림이 보인다.
     */
    DB_INTEGRITY(FailureKind.TRANSIENT, anyOf(DataIntegrityViolationException.class)),

    /** 인자가 틀렸다 — 같은 레코드를 다시 읽으면 같은 인자다. */
    ARGUMENT(FailureKind.DETERMINISTIC, anyOf(IllegalArgumentException.class)),

    /**
     * 도메인 예외 — 검증 실패 · 없는 대상 · 잘못된 전이. 비즈니스 규칙 위반({@code EventRejectedException})은 여기 오지 않는다 —
     * {@code IdempotentConsumer} 가 흡수하고 커밋한다(§4.6).
     */
    DOMAIN(FailureKind.DETERMINISTIC, anyOf(DomainException.class)),

    /** 그 밖의 전부 — 애매하면 일시적이다. 어떤 예외에도 걸리지 않는 행이고, 위의 어느 행도 걸리지 않을 때의 판정이다. */
    OTHER(FailureKind.TRANSIENT, failure -> false);

    /**
     * {@link #DESERIALIZATION} 행의 타입 — 에러 핸들러가 재시도 목록에서 빼는 타입과 같은 목록이다. 앞의 둘은 이 저장소의 것이고,
     * 뒤의 여섯은 Spring Kafka 의 기본 치명 목록({@code ExceptionClassifier.defaultFatalExceptionsList()})이다 — 테스트가 대조한다.
     *
     * @return 즉시 DLQ 로 가는 타입들
     */
    public static List<Class<? extends Exception>> immediateDlqTypes() {
        return Types.IMMEDIATE_DLQ;
    }

    private final FailureKind kind;
    private final Predicate<Throwable> matches;

    ConsumeFailure(FailureKind kind, Predicate<Throwable> matches) {
        this.kind = kind;
        this.matches = matches;
    }

    /**
     * 실패를 표의 한 행으로 판정한다.
     *
     * @param failure 리스너 실패 — 겉옷째 준다. 원인 사슬 전체를 본다
     * @return 사슬 어딘가에 걸리는 첫 행, 없으면 {@link #OTHER}
     */
    public static ConsumeFailure of(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        List<Throwable> chain = chain(failure);
        for (ConsumeFailure row : values()) {
            if (chain.stream().anyMatch(row.matches)) {
                return row;
            }
        }
        return OTHER;
    }

    /**
     * @return 판정 — 일시적이면 끝없이 재시도, 결정적이면 DLQ(즉시 또는 3회 뒤)
     */
    public FailureKind kind() {
        return kind;
    }

    /**
     * @return {@code dawnline_event_retry_total} 의 {@code reason} 값 — 상수 이름의 소문자
     */
    public String reason() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * @return 재시도 경로에 드는 행의 {@link #reason()} 전부, 표의 순서대로 — 카탈로그의 닫힌 값과 같아야 한다
     */
    public static List<String> retryReasons() {
        return Arrays.stream(values())
                .filter(row -> row != DESERIALIZATION)
                .map(ConsumeFailure::reason)
                .toList();
    }

    private static Predicate<Throwable> anyOf(Class<?>... types) {
        return anyOf(List.of(types));
    }

    private static Predicate<Throwable> anyOf(List<? extends Class<?>> types) {
        return failure -> types.stream().anyMatch(type -> type.isInstance(failure));
    }

    /** enum 상수의 인자에서 정적 필드를 읽을 수 없어서 목록을 중첩 클래스에 둔다. */
    private static final class Types {

        static final List<Class<? extends Exception>> IMMEDIATE_DLQ = List.of(
                JacksonException.class, NonRetryableEventException.class,
                DeserializationException.class, MessageConversionException.class, ConversionException.class,
                MethodArgumentResolutionException.class, NoSuchMethodException.class, ClassCastException.class);
    }

    /** 원인 사슬 — 겉에서 안으로. 순환하는 사슬도 끝난다. */
    private static List<Throwable> chain(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }
}
