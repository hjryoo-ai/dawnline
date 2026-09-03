package com.dawnline.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.order.application.port.in.OrderAccepted;
import com.dawnline.order.application.port.out.IdempotencyClaim;
import com.dawnline.order.application.port.out.IdempotencyRecord;
import com.dawnline.order.application.port.out.IdempotencyRecords;
import com.dawnline.order.application.port.out.IdempotencyStatus;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.order.domain.ServiceTier;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code idempotency_keys} 업서트 (DESIGN.md §5.1, ADR-018) — 실제 PostgreSQL 18.
 *
 * <p>이 테스트가 없으면 확인되지 않는 것들이다.
 *
 * <ol>
 *   <li>{@code ON CONFLICT ... DO UPDATE ... WHERE} 가 실제로 이미 완료된 행을 막는가.
 *       멱등의 마지막 방어선이고, 단위 테스트로는 문자열만 볼 수 있다.</li>
 *   <li>{@code CAST(:body AS jsonb)} 로 바인딩한 값이 진짜 {@code jsonb} 로 저장되는가.
 *       문자열로 들어가면 나중에 JSON 연산자를 쓰는 순간 깨진다.</li>
 *   <li>{@code CHAR(64)} 인 {@code request_hash} 가 공백 패딩 없이 돌아오는가 — 패딩이 남으면
 *       지문 비교가 <em>항상</em> 실패해 모든 재요청이 422 가 된다.</li>
 *   <li>{@code TIMESTAMPTZ} 왕복에서 {@link Instant} 가 그대로인가.</li>
 * </ol>
 */
@SpringBootTest(classes = OrderApplication.class)
@DisplayName("IdempotencyRecordsIT — 멱등 기록 업서트")
class IdempotencyRecordsIT extends OrderIntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00.123456Z");

    @Autowired
    private IdempotencyRecords records;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions() {
        return new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void clear() {
        transactions().executeWithoutResult(status ->
                entityManager.createNativeQuery("DELETE FROM idempotency_keys").executeUpdate());
    }

    private static OrderAccepted accepted(UUID orderId) {
        return new OrderAccepted(orderId, OrderStatus.PLACED, ServiceTier.DAWN,
                NOW.plus(Duration.ofHours(15)), NOW.plus(Duration.ofHours(22)), NOW);
    }

    private static IdempotencyClaim claim(String key, String hash) {
        return new IdempotencyClaim(key, hash, NOW, NOW.plus(Duration.ofHours(24)));
    }

    private static String hash(char c) {
        return String.valueOf(c).repeat(64);
    }

    @Test
    void 처음_보는_키는_빈_값이다() {
        Optional<IdempotencyRecord> found = transactions().execute(status -> records.find("없는-키"));

        assertThat(found).isEmpty();
    }

    @Test
    void 완료_기록을_쓰고_응답까지_그대로_되살린다() {
        UUID orderId = Ids.newId();
        OrderAccepted response = accepted(orderId);

        Boolean claimed = transactions().execute(status ->
                records.complete(claim("idem-1", hash('a')), 201, response));

        assertThat(claimed).isTrue();
        IdempotencyRecord record = transactions().execute(status -> records.find("idem-1")).orElseThrow();
        // CHAR(64) 는 조회 시 공백 패딩이 붙을 수 있다. 붙은 채로 비교하면 모든 재요청이 422 가 된다.
        assertThat(record.requestHash()).isEqualTo(hash('a')).hasSize(64);
        assertThat(record.status()).isEqualTo(IdempotencyStatus.DONE);
        assertThat(record.responseCode()).isEqualTo(201);
        // TIMESTAMPTZ 왕복 후에도 마이크로초까지 살아 있어야 한다.
        assertThat(record.response()).isEqualTo(response);
    }

    @Test
    void 저장된_본문은_문자열이_아니라_jsonb_다() {
        transactions().execute(status -> records.complete(claim("idem-2", hash('b')), 201, accepted(Ids.newId())));

        String type = transactions().execute(status -> (String) entityManager
                .createNativeQuery("SELECT jsonb_typeof(response_body) FROM idempotency_keys WHERE idem_key = 'idem-2'")
                .getSingleResult());

        assertThat(type).isEqualTo("object");
    }

    @Test
    void 이미_완료된_키는_두_번째_요청이_덮어쓰지_못한다() {
        // 멱등의 마지막 방어선. 0행이 곧 "내가 졌다" 이고, 호출자는 그것으로 롤백을 결정한다.
        UUID first = Ids.newId();
        transactions().execute(status -> records.complete(claim("idem-3", hash('c')), 201, accepted(first)));

        Boolean second = transactions().execute(status ->
                records.complete(claim("idem-3", hash('c')), 201, accepted(Ids.newId())));

        assertThat(second).isFalse();
        IdempotencyRecord record = transactions().execute(status -> records.find("idem-3")).orElseThrow();
        assertThat(record.response()).isNotNull();
        assertThat(record.response().orderId()).as("첫 번째 응답이 그대로 남아야 한다").isEqualTo(first);
    }

    @Test
    void 처리중_행은_완료로_덮어쓸_수_있다() {
        // order-service 는 IN_PROGRESS 를 쓰지 않지만(ADR-018), 업서트의 WHERE 절이 그 값을 조건으로
        // 삼으므로 그 경로가 실제로 동작하는지 확인해 둔다.
        transactions().executeWithoutResult(status -> entityManager.createNativeQuery("""
                INSERT INTO idempotency_keys (idem_key, request_hash, status, created_at, expires_at)
                VALUES ('idem-4', :hash, 'IN_PROGRESS', :now, :expires)
                """)
                .setParameter("hash", hash('d'))
                .setParameter("now", NOW)
                .setParameter("expires", NOW.plus(Duration.ofHours(24)))
                .executeUpdate());

        IdempotencyRecord inProgress = transactions().execute(status -> records.find("idem-4")).orElseThrow();
        assertThat(inProgress.status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(inProgress.response()).isNull();

        Boolean claimed = transactions().execute(status ->
                records.complete(claim("idem-4", hash('d')), 201, accepted(Ids.newId())));

        assertThat(claimed).isTrue();
        assertThat(transactions().execute(status -> records.find("idem-4")).orElseThrow().status())
                .isEqualTo(IdempotencyStatus.DONE);
    }

    @Test
    void 트랜잭션이_롤백되면_기록도_사라진다() {
        // 주문 저장이 실패했는데 멱등 기록만 남으면, 그 키로는 영영 주문할 수 없게 된다.
        try {
            transactions().executeWithoutResult(status -> {
                records.complete(claim("idem-5", hash('e')), 201, accepted(Ids.newId()));
                throw new IllegalStateException("주문 저장 실패를 흉내 낸다");
            });
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessageContaining("흉내");
        }

        Optional<IdempotencyRecord> afterRollback = transactions().execute(status -> records.find("idem-5"));
        assertThat(afterRollback).isEmpty();
    }
}
