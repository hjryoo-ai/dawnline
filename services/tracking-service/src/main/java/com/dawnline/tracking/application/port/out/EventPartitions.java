package com.dawnline.tracking.application.port.out;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * {@code shipment_events} 의 일 파티션 관리 (DESIGN.md §5.4, §7.1).
 *
 * <p>이름 규칙({@code shipment_events_YYYYMMDD})과 경계 계산은 <strong>마이그레이션의 함수
 * 둘에만</strong> 있다. 이 포트는 날짜를 넘기고 개수를 돌려받을 뿐이다 — 이름을 자바에서도
 * 만들면 규칙이 두 곳이 되고, 둘은 갈라진다.
 *
 * <p>구현은 {@code adapter.out.persistence} 에 있고, DDL 이므로 트랜잭션에 참여하지 않는다.
 */
public interface EventPartitions {

    /**
     * {@code from} 부터 {@code days} 일치의 파티션을 만든다. 이미 있는 날은 건너뛴다.
     *
     * @param from 첫 날 (UTC 기준 날짜)
     * @param days 만들 일수. 1 이상
     * @return 실제로 만든 파티션 수
     */
    int ensure(LocalDate from, int days);

    /**
     * {@code before} 이전 날짜의 파티션을 지운다 (§5.4 보존 30일).
     *
     * @param before 이 날짜보다 앞선 파티션이 대상이다. 경계일 자신은 남는다
     * @return 지운 파티션 수
     */
    int dropBefore(LocalDate before);

    /**
     * 가장 늦은 파티션의 날짜.
     *
     * @return 파티션이 하나도 없으면 {@code null}
     */
    @Nullable LocalDate lastPartitionDay();
}
