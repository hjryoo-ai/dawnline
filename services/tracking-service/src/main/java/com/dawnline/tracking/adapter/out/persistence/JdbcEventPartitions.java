package com.dawnline.tracking.adapter.out.persistence;

import com.dawnline.tracking.application.port.out.EventPartitions;
import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link EventPartitions} 의 PostgreSQL 구현 — 마이그레이션이 정의한 함수 셋을 부른다
 * (DESIGN.md §5.4, {@code V1__tracking.sql}).
 *
 * <p>이 클래스에 SQL 문자열 조립이 없다. 파티션 이름과 경계는 함수가 만들고 여기서는 날짜만
 * 바인딩 파라미터로 넘긴다 — 동적 DDL 을 자바에서 만들지 않는 이유는 규칙을 한 곳에 두려는
 * 것이면서, 이름에 들어갈 값이 애초에 문자열로 흐르지 않게 하려는 것이기도 하다.
 */
public class JdbcEventPartitions implements EventPartitions {

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc 이 서비스의 데이터소스
     */
    public JdbcEventPartitions(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public int ensure(LocalDate from, int days) {
        Integer created = jdbc.queryForObject(
                "SELECT tracking_ensure_event_partitions(?, ?)", Integer.class, from, days);
        return created == null ? 0 : created;
    }

    @Override
    public int dropBefore(LocalDate before) {
        Integer dropped = jdbc.queryForObject(
                "SELECT tracking_drop_event_partitions(?)", Integer.class, before);
        return dropped == null ? 0 : dropped;
    }

    @Override
    public @Nullable LocalDate lastPartitionDay() {
        return jdbc.queryForObject("SELECT tracking_last_event_partition()", LocalDate.class);
    }
}
