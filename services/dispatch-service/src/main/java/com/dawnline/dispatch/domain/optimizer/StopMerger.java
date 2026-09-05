package com.dawnline.dispatch.domain.optimizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 후보를 방문 지점으로 통합한다 (DESIGN.md §6.5 1단계).
 *
 * <h2>통합 조건</h2>
 * <strong>같은 geohash7(약 153 m) + 같은 약속창</strong>이다. 약속창을 조건에 넣는 이유: 같은
 * 건물의 두 주문이라도 하나는 오전, 하나는 오후 창이면 한 번에 배송할 수 없다. 좌표만 보고 묶으면
 * 계획은 한 번 들르는데 현실은 두 번 들러야 한다.
 *
 * <h2>합쳐지는 값</h2>
 * 중량·부피는 더하고 냉장·위험물은 OR, 서비스 시간은 더하고 우선도는 최댓값이다. 서비스 시간을
 * 더하는 이유는 하차·전달이 주문마다 일어나기 때문이고, 우선도가 최댓값인 이유는 VIP 한 명이
 * 섞인 stop 을 뒤로 미루면 그 VIP 가 늦기 때문이다.
 *
 * <h2>왜 값어치가 있는가</h2>
 * 이동 시간이 사라진다 — 같은 지점의 두 주문을 따로 방문하면 그 사이에 왕복이 생긴다. 그리고
 * 거리 계산이 {@code O(n²)} 이므로 n 이 줄면 계획 시간이 제곱으로 줄어든다(§6.7).
 *
 * <p>통합은 <strong>선택적</strong>이다. {@link Stop#of(Candidate)} 로 묶지 않은 결과와 비교할 수
 * 있어야 통합의 값어치를 수치로 말할 수 있다(§6.9).
 */
public final class StopMerger {

    private StopMerger() {
    }

    /**
     * 통합한다. 입력 순서를 유지하므로 <strong>같은 입력이면 같은 순서의 결과</strong>가 나온다
     * (불변규칙 12 — seed 가 같으면 결과가 같아야 한다).
     *
     * @param candidates 후보들
     */
    public static List<Stop> merge(List<Candidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        Map<Candidate.MergeKey, List<Candidate>> grouped = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            grouped.computeIfAbsent(candidate.mergeKey(), key -> new ArrayList<>()).add(candidate);
        }
        return grouped.values().stream().map(StopMerger::combine).toList();
    }

    private static Stop combine(List<Candidate> group) {
        if (group.size() == 1) {
            return Stop.of(group.getFirst());
        }
        Candidate first = group.getFirst();
        Parcel parcel = Parcel.EMPTY;
        int serviceSeconds = 0;
        int priority = 0;
        List<OrderId> orderIds = new ArrayList<>(group.size());
        for (Candidate candidate : group) {
            parcel = parcel.plus(candidate.parcel());
            serviceSeconds = Math.addExact(serviceSeconds, candidate.serviceSeconds());
            priority = Math.max(priority, candidate.priority());
            orderIds.add(candidate.id());
        }
        // 대표점은 첫 후보의 좌표다. 같은 geohash7 안이라 최대 153 m 차이이고, 무게중심을 쓰면
        // 아무도 살지 않는 좌표가 나온다 — 기사가 갈 곳은 실재하는 주소여야 한다.
        return new Stop(first.point(), orderIds, parcel, first.promised(), serviceSeconds, priority);
    }
}
