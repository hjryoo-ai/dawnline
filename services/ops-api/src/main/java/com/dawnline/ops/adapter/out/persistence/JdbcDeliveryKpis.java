package com.dawnline.ops.adapter.out.persistence;

import com.dawnline.ops.application.port.out.DeliveryKpis;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * {@code kpi_delivery_hourly} 어댑터 (DESIGN.md §5.5).
 *
 * <p>뷰만 읽는다 — {@code rm_orders} 를 직접 세지 않는다. {@code bucket_hour} 술어가 뷰 안으로 내려가
 * {@code ix_rmo_delivery_hour} 의 식과 만나야 인덱스를 탄다(peak 30일 474 → 39.6 ms,
 * {@code docs/benchmarks/phase6-kpi-hourly-views-index.md}).
 *
 * <p>캠프를 모르는 행({@code camp_id IS NULL})도 읽는다 — 그 행에는 정시율의 칸이 없고(뷰가 0 으로 센다)
 * {@code outcome_without_promise} 만 있다. 빼고 읽으면 「fulfillment.planned 가 안 온 결과」가 빠진 수에서도
 * 빠진다.
 */
public class JdbcDeliveryKpis implements DeliveryKpis {

    static final String SUM_BY_CAMP_SQL = """
            SELECT camp_id, sum(delivered) AS delivered, sum(failed) AS failed,
                   sum(on_time_promised) AS on_time_promised, sum(on_time_revised) AS on_time_revised,
                   sum(outcome_without_promise) AS outcome_without_promise
              FROM kpi_delivery_hourly
             WHERE bucket_hour >= ? AND bucket_hour <= ?
             GROUP BY camp_id
            """;

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc JDBC 템플릿
     */
    public JdbcDeliveryKpis(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public DeliveryWindow window(Instant firstBucket, Instant lastBucket) {
        List<CampDeliveries> camps = new ArrayList<>();
        long[] withoutPromise = {0};
        jdbc.query(SUM_BY_CAMP_SQL, (RowCallbackHandler) rs -> {
            withoutPromise[0] += rs.getLong("outcome_without_promise");
            UUID campId = rs.getObject("camp_id", UUID.class);
            if (campId != null) {
                camps.add(new CampDeliveries(campId,
                        rs.getLong("delivered"), rs.getLong("failed"),
                        rs.getLong("on_time_promised"), rs.getLong("on_time_revised")));
            }
        },
                // TIMESTAMPTZ 에는 OffsetDateTime 으로 넘긴다 — 드라이버가 Instant 를 직접 받지 않는다.
                firstBucket.atOffset(ZoneOffset.UTC), lastBucket.atOffset(ZoneOffset.UTC));
        return new DeliveryWindow(camps, withoutPromise[0]);
    }
}
