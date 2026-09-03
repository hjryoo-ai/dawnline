package com.dawnline.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dawnline.common.Ids;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.order.application.port.in.OrderAccepted;
import com.dawnline.order.application.port.out.IdempotencyClaim;
import com.dawnline.order.application.port.out.IdempotencyRecord;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.order.domain.ServiceTier;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code idempotency_keys} 어댑터의 SQL 조립 (DESIGN.md §5.1, ADR-018).
 *
 * <p>실제 DB 왕복은 {@code IdempotencyRecordsIT} 가 본다. 여기서는 DB 없이 확인할 수 있는 것 —
 * <em>어떤 문장이 만들어지는가</em>와 <em>행 수를 어떻게 해석하는가</em> — 를 본다.
 */
@DisplayName("JpaIdempotencyRecords — 업서트 SQL 과 행 매핑")
class JpaIdempotencyRecordsTest {

    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");
    private static final UUID ORDER_ID = Ids.newId();

    private EntityManager entityManager;
    private Query query;
    private ObjectMapper json;
    private JpaIdempotencyRecords records;

    @BeforeEach
    void setUp() {
        entityManager = mock(EntityManager.class);
        query = mock(Query.class);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        // 이벤트 계약용 매퍼는 아니지만, 설정(ISO 시각·미지 필드 무시)이 애플리케이션 매퍼와 같아
        // 단위 테스트에서 그대로 쓸 수 있다. 프로덕션에서는 Boot 의 ObjectMapper 가 주입된다.
        json = EventJson.standardMapper();
        records = new JpaIdempotencyRecords(entityManager, json);
    }

    private static OrderAccepted accepted() {
        return new OrderAccepted(ORDER_ID, OrderStatus.PLACED, ServiceTier.DAWN,
                NOW.plus(Duration.ofHours(15)), NOW.plus(Duration.ofHours(22)), NOW);
    }

    private static IdempotencyClaim claim() {
        return new IdempotencyClaim("idem-1", "a".repeat(64), NOW, NOW.plus(Duration.ofHours(24)));
    }

    /**
     * 네이티브 쿼리의 결과 한 줄. {@code List.of(new Object[]{...})} 를 그대로 쓰면 가변인자로 풀려
     * 4개짜리 리스트가 되고, null 컬럼이 있으면 {@code List.of} 가 거부하기까지 한다.
     */
    private static List<Object> rows(Object... row) {
        return java.util.Collections.singletonList((Object) row);
    }

    private String capturedSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        return sql.getValue();
    }

    @Test
    void 조회는_jsonb_를_text_로_캐스팅해_읽는다() {
        when(query.getResultList()).thenReturn(List.of());

        records.find("idem-1");

        String sql = capturedSql();
        assertThat(sql).contains("CAST(response_body AS text)");
        // ::text 로 쓰면 Hibernate 의 명명 파라미터 파서가 :text 를 파라미터로 본다.
        assertThat(sql).doesNotContain("::");
        verify(query).setParameter("key", "idem-1");
    }

    @Test
    void 없는_키는_빈_값이다() {
        when(query.getResultList()).thenReturn(List.of());

        assertThat(records.find("idem-1")).isEmpty();
    }

    @Test
    void 저장된_행을_응답까지_되살린다() {
        String body = json.writeValueAsString(accepted());
        when(query.getResultList()).thenReturn(rows("a".repeat(64), (short) 201, body));

        Optional<IdempotencyRecord> found = records.find("idem-1");

        assertThat(found).isPresent();
        IdempotencyRecord record = found.orElseThrow();
        assertThat(record.requestHash()).isEqualTo("a".repeat(64));
        assertThat(record.responseCode()).isEqualTo(201);
        assertThat(record.response()).isEqualTo(accepted());
    }

    @Test
    void CHAR_64_의_공백_패딩을_지운다() {
        // 패딩이 남으면 지문 비교가 항상 실패해 모든 재요청이 422 가 된다.
        String body = json.writeValueAsString(accepted());
        when(query.getResultList()).thenReturn(rows("a".repeat(64) + "   ", (short) 201, body));

        assertThat(records.find("idem-1").orElseThrow().requestHash()).isEqualTo("a".repeat(64));
    }

    @Test
    void 삽입은_이미_있는_행을_건드리지_않는다() {
        // 멱등의 마지막 방어선이다. DO UPDATE 였다면 두 번째 요청이 첫 번째의 응답을 지운다.
        when(query.executeUpdate()).thenReturn(1);

        records.complete(claim(), 201, accepted());

        String sql = capturedSql();
        assertThat(sql).contains("ON CONFLICT (idem_key) DO NOTHING");
        assertThat(sql).doesNotContain("DO UPDATE");
        assertThat(sql).contains("CAST(:body AS jsonb)");
    }

    @Test
    void 만료_삭제는_ctid_와_LIMIT_으로_끊는다() {
        // PostgreSQL 의 DELETE 에는 LIMIT 절이 없다. ctid 서브쿼리가 그 자리를 대신한다.
        when(query.executeUpdate()).thenReturn(7);

        assertThat(records.deleteExpired(NOW, 1000)).isEqualTo(7);

        String sql = capturedSql();
        assertThat(sql).contains("WHERE ctid IN (");
        assertThat(sql).contains("ORDER BY expires_at");
        assertThat(sql).contains("LIMIT :limit");
        verify(query).setParameter("now", NOW);
        verify(query).setParameter("limit", 1000);
    }

    @Test
    void 만료_삭제의_배치_크기는_1_이상이어야_한다() {
        assertThatThrownBy(() -> records.deleteExpired(NOW, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
    }

    @Test
    void 업서트_파라미터가_claim_에서_온다() {
        when(query.executeUpdate()).thenReturn(1);

        records.complete(claim(), 201, accepted());

        verify(query).setParameter("key", "idem-1");
        verify(query).setParameter("hash", "a".repeat(64));
        verify(query).setParameter("code", 201);
        verify(query).setParameter("createdAt", NOW);
        verify(query).setParameter("expiresAt", NOW.plus(Duration.ofHours(24)));
        verify(query).setParameter("body", json.writeValueAsString(accepted()));
    }

    @Test
    void 한_행이_삽입되면_주인이_된_것이고_0행이면_아니다() {
        when(query.executeUpdate()).thenReturn(1);
        assertThat(records.complete(claim(), 201, accepted())).isTrue();

        when(query.executeUpdate()).thenReturn(0);
        assertThat(records.complete(claim(), 201, accepted())).isFalse();
    }

    @Test
    void null_인자는_거부한다() {
        assertThatThrownBy(() -> records.find(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> records.complete(null, 201, accepted()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> records.complete(claim(), 201, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> records.deleteExpired(null, 10)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JpaIdempotencyRecords(null, json))
                .isInstanceOf(NullPointerException.class);
    }
}
