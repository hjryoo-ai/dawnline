package com.dawnline.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.error.CommonErrorCode;
import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.IllegalStateTransitionException;
import com.dawnline.common.error.ValidationException;
import com.dawnline.messaging.FailureKind;
import com.dawnline.observability.DawnlineMetrics;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import io.lettuce.core.RedisCommandTimeoutException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.kafka.listener.ExceptionClassifier;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 소비 측 경계표의 행마다 한 경우 — 그리고 그 경우가 행의 음성 표본이다 (ADR-015 후속 정정 2026-09-25).
 *
 * <p><strong>일시적 행</strong>은 「그 예외를 결정적 예외({@link IllegalArgumentException})로 감싼 것」이 일시적이라는 것을 본다 —
 * 기본값이 이미 일시적이라 그 예외 단독으로는 행을 지워도 판정이 같다. 행을 지우면 감싼 경우가 {@code ARGUMENT} 로 뒤집혀 빨강이
 * 된다. 행의 판정을 뒤집어도 빨강이다. <strong>결정적 행</strong>은 그 예외 단독이 결정적이라는 것을 본다 — 행을 지우면 {@code OTHER}
 * 로 떨어져 빨강이다. {@code OTHER} 는 기본값을 결정적으로 바꾸면 빨강이다. HTTP 행(해당 없음)의 표본은 ArchUnit 규칙 12 에 있다.
 *
 * <p>리스너 실패는 컨테이너가 {@link ListenerExecutionFailedException} 으로 감싸서 에러 핸들러에 준다. 경우마다 그 겉옷을 입혀서
 * 판정한다 — 실제로 핸들러가 받는 모양이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ConsumeFailureTest {

    @Nested
    class 행마다_한_경우 {

        @Test
        void deserialization_은_즉시_DLQ_로_가는_결정적이다() {
            DeserializationException failure = new DeserializationException("봉투가 JSON 이 아니다", new byte[0], false,
                    new IllegalStateException("파서"));

            assertRow(failure, ConsumeFailure.DESERIALIZATION, FailureKind.DETERMINISTIC);
        }

        @Test
        void redis_연결_실패는_DB_자원_행보다_먼저_redis_다() {
            // RedisConnectionFailureException 은 DataAccessResourceFailureException 이다 — 행 순서가 이 판정을 만든다.
            assertRow(vetoed(new RedisConnectionFailureException("redis:6379 에 연결할 수 없다")),
                    ConsumeFailure.REDIS, FailureKind.TRANSIENT);
        }

        @Test
        void redis_명령_시간_초과는_번역된_겉이_DB_시간_초과여도_redis_다() {
            // Spring Data Redis 는 Lettuce 의 시간 초과를 QueryTimeoutException 으로 번역한다 — 안쪽에 Lettuce 가 있다.
            QueryTimeoutException translated = new QueryTimeoutException("Redis command timed out",
                    new RedisCommandTimeoutException("Command timed out after 2 second(s)"));

            assertRow(vetoed(translated), ConsumeFailure.REDIS, FailureKind.TRANSIENT);
        }

        @Test
        void db_connection_은_RB_02_재현의_모양이다() {
            // 관측된 사슬(RB-02 §3): JPA 트랜잭션 시작 → Hibernate 연결 예외 → Hikari 의 30초 대기.
            CannotCreateTransactionException observed = new CannotCreateTransactionException(
                    "Could not open JPA EntityManager for transaction",
                    new JDBCConnectionException("Unable to acquire JDBC Connection",
                            new SQLTransientConnectionException(
                                    "HikariPool-1 - Connection is not available, request timed out after 30001ms.")));

            assertRow(vetoed(observed), ConsumeFailure.DB_CONNECTION, FailureKind.TRANSIENT);
        }

        @Test
        void db_resource_는_끊긴_세션이다() {
            assertRow(vetoed(new DataAccessResourceFailureException("terminating connection due to administrator command",
                    new SQLRecoverableException("57P01"))), ConsumeFailure.DB_RESOURCE, FailureKind.TRANSIENT);
        }

        @Test
        void db_transient_는_시간_초과와_락이다() {
            assertRow(vetoed(new QueryTimeoutException("canceling statement due to statement timeout",
                    new SQLTimeoutException("57014"))), ConsumeFailure.DB_TRANSIENT, FailureKind.TRANSIENT);
            assertRow(vetoed(new CannotAcquireLockException("deadlock detected")),
                    ConsumeFailure.DB_TRANSIENT, FailureKind.TRANSIENT);
        }

        @Test
        void db_integrity_는_애매해서_일시적이다() {
            assertRow(vetoed(new DataIntegrityViolationException("violates foreign key constraint")),
                    ConsumeFailure.DB_INTEGRITY, FailureKind.TRANSIENT);
            assertRow(vetoed(new DuplicateKeyException("duplicate key value violates unique constraint")),
                    ConsumeFailure.DB_INTEGRITY, FailureKind.TRANSIENT);
        }

        @Test
        void argument_는_결정적이다() {
            assertRow(new IllegalArgumentException("알 수 없는 티어: FOO"), ConsumeFailure.ARGUMENT, FailureKind.DETERMINISTIC);
        }

        @Test
        void domain_은_결정적이다() {
            assertRow(new ValidationException("delivery.status 에 orderIds 가 없습니다"),
                    ConsumeFailure.DOMAIN, FailureKind.DETERMINISTIC);
            assertRow(new IllegalStateTransitionException("Order", "CANCELLED", "DISPATCHED"),
                    ConsumeFailure.DOMAIN, FailureKind.DETERMINISTIC);
        }

        @Test
        void other_는_애매하면_일시적이다() {
            assertRow(new NullPointerException("campId"), ConsumeFailure.OTHER, FailureKind.TRANSIENT);
            assertRow(new IllegalStateException("예상하지 못한 상태"), ConsumeFailure.OTHER, FailureKind.TRANSIENT);
        }
    }

    @Nested
    class 판정의_모양 {

        @Test
        void 일시적_원인은_도메인_예외에_싸여_와도_일시적이다() {
            // 겉이 결정적 행이어도 사슬 안의 일시적 행이 먼저다 — 장애가 DLQ 로 흐르지 않는다.
            DomainException wrapped = new DomainException(CommonErrorCode.VALIDATION_FAILED, "검증 중 조회 실패", Map.of(),
                    new CannotCreateTransactionException("x", new SQLTransientConnectionException("timeout")));

            assertThat(ConsumeFailure.of(wrapped)).isEqualTo(ConsumeFailure.DB_CONNECTION);
        }

        @Test
        void 순환하는_원인_사슬도_끝난다() {
            IllegalStateException first = new IllegalStateException("a");
            IllegalStateException second = new IllegalStateException("b", first);
            first.initCause(second);

            assertThat(ConsumeFailure.of(first)).isEqualTo(ConsumeFailure.OTHER);
        }

        @Test
        void 즉시_DLQ_의_타입은_스프링_기본_치명_목록과_이_저장소의_둘이다() {
            // Spring Kafka 가 기본 목록을 바꾸면 여기서 안다 — 표의 첫 행이 실제 동작과 갈라지지 않게.
            Set<Class<?>> expected = new HashSet<>(ExceptionClassifier.defaultFatalExceptionsList());
            expected.add(tools.jackson.core.JacksonException.class);
            expected.add(NonRetryableEventException.class);

            assertThat(new HashSet<Class<?>>(ConsumeFailure.immediateDlqTypes())).isEqualTo(expected);
        }

        @Test
        void 경계표는_선택_의존의_타입을_참조하지_않는다() {
            // spring-jdbc · Hibernate · Redis 는 이 라이브러리의 선택 의존이다. 타입으로 참조하면 그것이 없는 소비자(sim-runner)에서
            // enum 이 적재되지 못하고 에러 핸들러를 만들다 기동이 실패한다(관측(재현됨) — 2026-09-25 SimDriverIT). 이름으로 판정한다.
            ArchRuleDefinition.noClasses()
                    .that().haveFullyQualifiedName(ConsumeFailure.class.getName())
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.jdbc..", "org.hibernate..", "org.springframework.data.redis..", "io.lettuce..")
                    .check(new ClassFileImporter().importClasses(ConsumeFailure.class));
        }

        @Test
        void 재시도_사유는_즉시_DLQ_를_뺀_행_전부다() {
            assertThat(ConsumeFailure.retryReasons()).containsExactly(
                    "redis", "db_connection", "db_resource", "db_transient", "db_integrity", "argument", "domain", "other");
        }

        @Test
        void 재시도_사유는_카탈로그의_닫힌_값과_같다() {
            // ADR-060 결정 2 — 값이 enum 에서 오는 라벨은 그 enum 과 대조한다. 행이 느는 날 운영의 첫 등록이 아니라 빌드가 실패한다.
            assertThat(DawnlineMetrics.EVENT_RETRY.labels())
                    .filteredOn(label -> label.key().equals("reason"))
                    .singleElement()
                    .satisfies(label -> assertThat(label.values()).isEqualTo(ConsumeFailure.retryReasons()));
        }
    }

    /** 결정적 예외로 감싼다 — 일시적 행이 거부권을 쥐는지 본다. */
    private static IllegalArgumentException vetoed(Throwable cause) {
        return new IllegalArgumentException("결정적 겉옷", cause);
    }

    private static void assertRow(Throwable failure, ConsumeFailure row, FailureKind kind) {
        for (Throwable shape : List.of(failure, new ListenerExecutionFailedException("리스너 실패", failure))) {
            ConsumeFailure actual = ConsumeFailure.of(shape);
            assertThat(actual).as("%s 의 행", shape).isEqualTo(row);
            assertThat(actual.kind()).as("%s 의 판정", row).isEqualTo(kind);
        }
    }
}
