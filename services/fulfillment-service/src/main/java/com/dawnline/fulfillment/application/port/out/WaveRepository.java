package com.dawnline.fulfillment.application.port.out;

import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.Wave;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code waves} 저장소 (DESIGN.md §5.2).
 *
 * <p>웨이브는 {@code (campId, serviceTier, cutoffAt)} 당 하나이고, 그 UNIQUE 제약이 동시 편입의
 * 직렬화 지점이다 — 두 리스너가 같은 조합으로 동시에 INSERT 하면 한쪽만 산다.
 */
public interface WaveRepository {

    /**
     * 없으면 만든다 ({@code INSERT … ON CONFLICT DO NOTHING}).
     *
     * <p>먼저 조회하고 없으면 INSERT 하는 방식은 두 리스너가 같은 틈에 들어가면 둘 다 INSERT 를
     * 시도해 한쪽이 제약 위반으로 <em>예외</em>가 된다. 예외를 잡아 재조회하는 것과 결과는 같지만,
     * 그 경로는 정상 흐름에 예외를 쓰는 것이고 로그가 사고처럼 보인다 (ADR-018 과 같은 판단).
     *
     * @param wave 열려는 웨이브
     * @return 이 호출이 실제로 만들었으면 {@code true}, 이미 있었으면 {@code false}
     */
    boolean insertIfAbsent(Wave wave);

    /**
     * 자연키로 찾는다.
     *
     * @param campId      캠프
     * @param serviceTier 티어
     * @param cutoffAt    컷오프 ({@code order.placed} 가 싣고 온 값, ADR-020)
     */
    Optional<Wave> findByNaturalKey(UUID campId, ServiceTier serviceTier, Instant cutoffAt);

    /**
     * id 로 찾는다.
     *
     * @param id 웨이브 id
     */
    Optional<Wave> findById(UUID id);

    /**
     * 편입을 위해 행을 잠그고 찾는다 ({@code SELECT … FOR UPDATE}).
     *
     * <p>§7.1 이 비관적 락을 허용한 <strong>유일한</strong> 자리다. {@code order_count} 증감은
     * 읽고-고치고-쓰는 연산이라 낙관적 락으로 두면 편입이 몰릴 때 충돌 재시도가 그대로 처리량이
     * 된다. 트랜잭션을 짧게 유지하는 것이 조건이다.
     *
     * @param id 웨이브 id
     */
    Optional<Wave> findByIdForUpdate(UUID id);

    /**
     * 마감할 때가 된 웨이브들 ({@code status='OPEN' AND cutoff_at <= threshold}).
     *
     * <p>{@code threshold} 는 호출자가 {@code now − grace} 로 계산해 넘긴다 — 도메인의
     * {@link Wave#isDueForClosing} 과 같은 판정을 SQL 로 옮긴 것이고, 스케줄러가 매번 전량을
     * 읽어 도메인으로 거르지 않기 위해서다. {@code ix_waves_open_cutoff} 부분 인덱스를 탄다.
     *
     * @param cutoffAtOrBefore 이 시각까지의 컷오프
     * @param limit            한 번에 가져올 최대 개수
     */
    List<Wave> findDueForClosing(Instant cutoffAtOrBefore, int limit);

    /**
     * 변경을 반영한다 (낙관적 락).
     *
     * @param wave 변경된 웨이브
     */
    void update(Wave wave);

    /**
     * 보존 만료 웨이브를 배치로 지운다 (ADR-023 결정 3 — 90일).
     *
     * <p>계획이 끝난 웨이브만 지운다. 그리고 <strong>참조하는 주문 행이 없는 것만</strong> 지운다 —
     * ADR-023 은 "주문 행이 30일에 먼저 사라지므로 FK 는 자연히 만족된다" 고 적었지만 그것은 두
     * 보존 기간을 그렇게 고른 <em>결과</em>이지 강제되는 성질이 아니다. 어느 한쪽을 바꾸면 조용히
     * 깨지고, 증상은 정리 배치가 매일 FK 위반으로 실패하는 것이다.
     *
     * @param closedBefore 이 시각 이전에 마감된 웨이브
     * @param limit        한 트랜잭션에서 지울 최대 행 수
     * @return 삭제된 행 수
     */
    int deleteSettledClosedBefore(Instant closedBefore, int limit);
}
