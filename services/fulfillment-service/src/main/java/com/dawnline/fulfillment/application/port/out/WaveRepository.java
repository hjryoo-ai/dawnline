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
     * <strong>편입</strong>을 위해 행을 공유 잠금하고 찾는다 ({@code SELECT … FOR SHARE}, ADR-025).
     *
     * <p>편입이 웨이브 행에 요구하는 것은 하나뿐이다 — <em>내가 주문을 넣는 동안 이 웨이브가
     * 마감되지 않는다</em>. 공유 락이 그것을 준다. 공유 락끼리는 서로 막지 않으므로 같은 웨이브로
     * 몰리는 주문들이 병렬로 편입된다.
     *
     * <p>배타 락을 쓰면 §8.2 피크(컷오프 직전 600 rps 가 소수 웨이브에 몰림)에서 <strong>웨이브
     * 행 하나가 처리량 상한</strong>이 된다. 정확성을 위해 넣은 규칙이 부하가 몰리는 바로 그
     * 순간에 병목이 되는 형태였다.
     *
     * <p>낙관적 락으로 대신할 수 없다. 편입은 웨이브 행을 <em>쓰지 않으므로</em> 버전 충돌이
     * 나지 않고, 상태를 읽는 시점과 INSERT 사이에 마감이 끼어드는 창이 그대로 남는다.
     *
     * @param id 웨이브 id
     */
    Optional<Wave> findByIdForShare(UUID id);

    /**
     * <strong>마감</strong>을 위해 행을 배타 잠금하고 찾는다 ({@code SELECT … FOR UPDATE}).
     *
     * <p>진행 중인 편입(공유 락)이 <em>전부</em> 커밋될 때까지 기다린 뒤 잡힌다. 그래서 이 락을
     * 얻은 시점에는 새 편입이 없고, {@code CLOSING} 으로 바꾸고 나면 그 뒤에 오는 편입은 공유
     * 락에서 대기했다가 상태를 보고 다음 웨이브로 간다 — "마감된 웨이브에 주문이 새는" 창이
     * 여기서 닫힌다 (ADR-025).
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
