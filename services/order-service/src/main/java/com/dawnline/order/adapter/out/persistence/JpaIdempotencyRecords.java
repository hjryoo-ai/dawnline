package com.dawnline.order.adapter.out.persistence;

import com.dawnline.order.application.port.in.OrderAccepted;
import com.dawnline.order.application.port.out.IdempotencyClaim;
import com.dawnline.order.application.port.out.IdempotencyRecord;
import com.dawnline.order.application.port.out.IdempotencyRecords;
import com.dawnline.order.application.port.out.IdempotencyStatus;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link IdempotencyRecords} 의 JPA 구현 (DESIGN.md §5.1, ADR-018).
 *
 * <h2>왜 엔티티가 아니라 네이티브 SQL 인가</h2>
 * 이 어댑터의 정확성은 {@code ON CONFLICT ... DO UPDATE ... WHERE} 한 문장에 달려 있다.
 * 그 문장이 JPA 로는 표현되지 않는다 — {@code merge} 는 "있으면 덮어쓴다" 라서 이미 완료된 응답을
 * 뒤엎고, 그러면 두 번째 요청이 첫 번째의 답을 지운 뒤 자기 답을 준다. 멱등이 아니다.
 *
 * <p>{@code WHERE idempotency_keys.status = 'IN_PROGRESS'} 가 그것을 막는다. 이미 {@code DONE} 인
 * 행에는 0행이 걸리고, 0행이 곧 "내가 졌다" 는 신호다. 동시 요청은 첫 번째가 커밋할 때까지 인덱스에서
 * 기다렸다가 그 결과를 본다.
 *
 * <h2>왜 애플리케이션 {@code ObjectMapper} 인가</h2>
 * {@code response_body} 는 <strong>HTTP 응답을 그대로 재생하기 위한</strong> 값이다(§5.1 1단계).
 * 그러니 저장할 때와 응답을 쓸 때가 같은 매퍼여야 한다. 이벤트 계약용
 * {@code EventJson} 은 여기 쓰지 않는다 — 그쪽은 서비스 간 계약이라 설정이 따로 굳어 있어야 한다.
 */
public class JpaIdempotencyRecords implements IdempotencyRecords {

    /**
     * {@code CAST(response_body AS text)} 로 읽는다. {@code ::text} 로 쓰면 Hibernate 의 명명 파라미터
     * 파서가 {@code :text} 를 파라미터로 본다.
     */
    private static final String FIND_SQL = """
            SELECT request_hash, status, response_code, CAST(response_body AS text)
              FROM idempotency_keys
             WHERE idem_key = :key
            """;

    private static final String COMPLETE_SQL = """
            INSERT INTO idempotency_keys
                   (idem_key, request_hash, status, response_code, response_body, created_at, expires_at)
            VALUES (:key, :hash, 'DONE', :code, CAST(:body AS jsonb), :createdAt, :expiresAt)
            ON CONFLICT (idem_key) DO UPDATE
               SET request_hash  = EXCLUDED.request_hash,
                   status        = 'DONE',
                   response_code = EXCLUDED.response_code,
                   response_body = EXCLUDED.response_body,
                   created_at    = EXCLUDED.created_at,
                   expires_at    = EXCLUDED.expires_at
             WHERE idempotency_keys.status = 'IN_PROGRESS'
            """;

    private final EntityManager entityManager;
    private final ObjectMapper json;

    /**
     * @param entityManager 현재 트랜잭션의 EntityManager
     * @param json          응답 본문 직렬화기 — 웹 어댑터가 응답을 쓸 때와 같은 매퍼
     */
    public JpaIdempotencyRecords(EntityManager entityManager, ObjectMapper json) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        Objects.requireNonNull(key, "key");
        List<?> rows = entityManager.createNativeQuery(FIND_SQL)
                .setParameter("key", key)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.getFirst();
        IdempotencyStatus status = statusOf((String) row[1]);
        Integer responseCode = row[2] == null ? null : ((Number) row[2]).intValue();
        return Optional.of(new IdempotencyRecord(
                ((String) row[0]).trim(), status, responseCode, responseOf(status, (String) row[3])));
    }

    @Override
    public boolean complete(IdempotencyClaim claim, int responseCode, OrderAccepted response) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(response, "response");
        int affected = entityManager.createNativeQuery(COMPLETE_SQL)
                .setParameter("key", claim.key())
                .setParameter("hash", claim.requestHash())
                .setParameter("code", responseCode)
                .setParameter("body", json.writeValueAsString(response))
                .setParameter("createdAt", claim.createdAt())
                .setParameter("expiresAt", claim.expiresAt())
                .executeUpdate();
        return affected == 1;
    }

    private static IdempotencyStatus statusOf(String stored) {
        try {
            return IdempotencyStatus.valueOf(stored.trim());
        } catch (IllegalArgumentException e) {
            // 컬럼이 VARCHAR 라 무엇이든 들어갈 수 있다. 조용히 DONE 으로 보면 남의 응답을 재생하게 된다.
            throw new IllegalStateException("알 수 없는 idempotency_keys.status: " + stored, e);
        }
    }

    private @Nullable OrderAccepted responseOf(IdempotencyStatus status, @Nullable String body) {
        if (status != IdempotencyStatus.DONE) {
            return null;
        }
        if (body == null) {
            throw new IllegalStateException("DONE 인데 response_body 가 비어 있습니다");
        }
        return json.readValue(body, OrderAccepted.class);
    }
}
