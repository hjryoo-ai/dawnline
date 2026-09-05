package com.dawnline.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 티어별 주문 컷오프 표 (DESIGN.md §2.2) — 순수 함수.
 *
 * <h2>왜 여기 있는가</h2>
 * [ADR-020](docs/adr/ADR-020-cutoff-ownership-wave-grace-promise-revision.md) 은 컷오프 계산을
 * order-service 한 곳에 두었다. 막으려던 것은 <strong>표의 복사본이 둘이 되는 것</strong>이다 —
 * 한쪽만 고치는 날 약속창과 웨이브가 어긋나기 때문이다.
 *
 * <p>그런데 약속 개정 경로(§5.2)에서 fulfillment 는 <em>다음 컷오프가 언제인지</em> 알아야 한다.
 * grace 를 넘겨 도착한 주문을 다음 웨이브로 보내야 하고, 그 웨이브가 없으면 만들어야 한다.
 * 그래서 <strong>구현 하나를 둘이 쓴다</strong>(ADR-020 후속 정정 2). 복사본이 위험한 이유는
 * 갈라지기 때문인데, 같은 클래스를 참조하면 갈라질 수 없다.
 *
 * <p>권위는 그대로 order-service 다. 이벤트에 {@code cutoffAt} 을 찍는 것은 접수 경로 한 곳이고,
 * 그 값이 이 클래스의 출력과 같다는 사실은 계약 테스트가 고정한다.
 *
 * <h2>티어를 이름(문자열)으로 받는 이유</h2>
 * 두 서비스는 {@code ServiceTier} enum 을 각자 정의하고(불변규칙 3 — 서비스 간 소스 의존 금지),
 * 공유되는 진실은 <strong>이벤트 계약의 enum 값</strong>이다({@code ServiceTierContractTest}).
 * {@code libs/common} 은 그 경계에 있으므로 경계의 어휘를 쓴다.
 *
 * <h2>서머타임</h2>
 * 컷오프는 <strong>벽시계 시각</strong>이다("10:00", "24:00"). 그래서 날짜와 시각을 각각 만든 뒤
 * 지역 시간대에 붙인다. {@code Asia/Seoul} 은 서머타임이 없어 지금은 차이가 없지만, 시간대를
 * 바꿔 쓸 때 길이가 아니라 벽시계가 유지되는 쪽이 맞다.
 */
public final class CutoffSchedule {

    /** 서비스 기준 시간대. */
    public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * §2.2 의 컷오프 표. 하루 안의 컷오프 시각들이고, {@code 24:00} 은 "그날이 끝나는 자정"
     * 이므로 <em>다음 날 00:00</em> 으로 표현한다.
     */
    private static final Map<String, List<LocalTime>> CUTOFFS = Map.of(
            // 전일 24:00 → 하루에 하나, 자정
            "DAWN", List.of(LocalTime.MIDNIGHT),
            "SAME_DAY", List.of(LocalTime.of(10, 0), LocalTime.of(14, 0)),
            "NEXT_DAY", List.of(LocalTime.MIDNIGHT));

    private final ZoneId zone;

    /**
     * @param zone 컷오프를 해석할 시간대
     */
    public CutoffSchedule(ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /** §2.2 의 기본값 — 서비스 기준 시간대. */
    public static CutoffSchedule standard() {
        return new CutoffSchedule(SERVICE_ZONE);
    }

    /**
     * 이 시각에 접수한 주문이 실릴 컷오프.
     *
     * <p><strong>경계는 포함하지 않는다.</strong> 정확히 10:00 에 접수한 주문은 10:00 컷오프가
     * 아니라 14:00 컷오프에 실린다 — 컷오프란 "이 시각까지 받는다" 이고, 그 시각에 도착한 것은
     * 이미 늦은 것으로 본다. order-service 의 기존 구현과 같은 규칙이다.
     *
     * @param tier     티어 이름 (계약의 enum 값)
     * @param placedAt 접수 시각
     */
    public Instant cutoffFor(String tier, Instant placedAt) {
        Objects.requireNonNull(placedAt, "placedAt");
        return firstCutoffAfter(tier, placedAt, false);
    }

    /**
     * 주어진 컷오프 <strong>다음</strong> 컷오프.
     *
     * <p>약속 개정 경로가 쓴다(ADR-020 후속 정정 2) — grace 를 넘겨 도착해 이미 마감된 웨이브의
     * {@code cutoffAt} 을 가진 주문이 갈 곳이다. 두 번 밀리면 두 번 부르면 된다.
     *
     * @param tier     티어 이름
     * @param cutoffAt 기준 컷오프
     */
    public Instant nextCutoffAfter(String tier, Instant cutoffAt) {
        Objects.requireNonNull(cutoffAt, "cutoffAt");
        return firstCutoffAfter(tier, cutoffAt, true);
    }

    /** 이 티어를 이 표가 아는가. 모르는 값은 계약이 깨진 것이므로 호출부가 알아야 한다. */
    public boolean knows(String tier) {
        return CUTOFFS.containsKey(tier);
    }

    /**
     * {@code from} 이후의 첫 컷오프.
     *
     * @param strictlyAfter 참이면 {@code from} 과 같은 시각은 건너뛴다. 거짓이어도 경계는
     *                      포함하지 않으므로 결과가 같지만, 의도를 이름으로 남긴다
     */
    private Instant firstCutoffAfter(String tier, Instant from, boolean strictlyAfter) {
        List<LocalTime> times = cutoffsOf(tier);
        ZonedDateTime local = from.atZone(zone);
        LocalDate date = local.toLocalDate();

        // 오늘과 내일이면 충분하다 — 하루에 컷오프가 하나 이상 있으므로 이틀 안에 반드시 찾는다.
        for (int dayOffset = 0; dayOffset <= 2; dayOffset++) {
            LocalDate day = date.plusDays(dayOffset);
            for (LocalTime time : times) {
                Instant candidate = at(day, time);
                if (candidate.isAfter(from)) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("컷오프를 찾지 못했습니다: tier=" + tier + " from=" + from);
    }

    private List<LocalTime> cutoffsOf(String tier) {
        return Optional.ofNullable(CUTOFFS.get(Objects.requireNonNull(tier, "tier")))
                .orElseThrow(() -> new IllegalArgumentException(
                        "DESIGN.md §2.2 에 없는 티어입니다: " + tier));
    }

    private Instant at(LocalDate date, LocalTime time) {
        return time.equals(LocalTime.MIDNIGHT)
                // 자정은 "그날이 끝나는 시각" 이므로 다음 날 00:00 이다. 서머타임 시작으로 00:00 이
                // 존재하지 않는 날이 있어 atStartOfDay 를 쓴다.
                ? date.plusDays(1).atStartOfDay(zone).toInstant()
                : date.atTime(time).atZone(zone).toInstant();
    }
}
