package com.dawnline.ops.adapter.out.persistence;

import com.dawnline.ops.application.port.out.DeliveryKpis;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code kpi_delivery_hourly} 어댑터 (DESIGN.md §5.5).
 *
 * <p>뷰만 읽는다 — {@code rm_orders} 를 직접 세지 않는다. {@code bucket_hour} 술어가 뷰 안으로 내려가
 * {@code ix_rmo_delivery_hour} 의 식과 만나야 인덱스를 탄다(peak 30일 491 → 37.8 ms,
 * {@code docs/benchmarks/phase6-kpi-hourly-views-index.md}).
 */
public class JdbcDeliveryKpis implements DeliveryKpis {

    static final String SUM_BY_CAMP_SQL = """
            SELECT camp_id, sum(delivered) AS delivered, sum(failed) AS failed,
                   sum(on_time_promised) AS on_time_promised, sum(on_time_revised) AS on_time_revised
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
    public List<CampDeliveries> sumByCamp(Instant firstBucket, Instant lastBucket) {
        return jdbc.query(SUM_BY_CAMP_SQL, (rs, n) -> new CampDeliveries(
                rs.getObject("camp_id", UUID.class),
                rs.getLong("delivered"), rs.getLong("failed"),
                rs.getLong("on_time_promised"), rs.getLong("on_time_revised")),
                // TIMESTAMPTZ 에는 OffsetDateTime 으로 넘긴다 — 드라이버가 Instant 를 직접 받지 않는다.
                firstBucket.atOffset(ZoneOffset.UTC), lastBucket.atOffset(ZoneOffset.UTC));
    }
}
