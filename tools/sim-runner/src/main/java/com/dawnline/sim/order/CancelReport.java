package com.dawnline.sim.order;

import java.util.Map;
import java.util.TreeMap;

/**
 * 취소의 결과 — 때와 응답별 (7-4 turbulent).
 *
 * @param beforePlanDecided   계획 전으로 뽑힌 주문 수(접수된 것)
 * @param beforePlan          계획 전 취소의 응답별 수
 * @param afterPublishDecided 발행 뒤로 뽑힌 주문 수(접수된 것)
 * @param afterPublish        발행 뒤 취소의 응답별 수 — 200 은 dispatch 까지 간 것, 409 는 order-service 가 먼저 막은 것
 * @param notSent             발행 뒤로 뽑혔는데 보내지 않은 수 — 계획이 끝나지 않았으면 여기 남는다
 * @param unknownId           201 본문에서 id 를 읽지 못해 부르지 못한 수
 */
public record CancelReport(int beforePlanDecided, Map<String, Integer> beforePlan, int afterPublishDecided,
        Map<String, Integer> afterPublish, int notSent, int unknownId) {

    /** @return 보고용 표 */
    public String toMarkdown() {
        StringBuilder out = new StringBuilder("| 취소 | 뽑힌 수 | 응답 |\n|---|---:|---|\n");
        out.append("| 계획 전 | ").append(beforePlanDecided).append(" | ").append(format(beforePlan)).append(" |\n");
        out.append("| 발행 뒤 | ").append(afterPublishDecided).append(" | ").append(format(afterPublish))
                .append(notSent > 0 ? " · 보내지 않음 " + notSent : "").append(" |\n");
        if (unknownId > 0) {
            out.append("| id 를 읽지 못함 | ").append(unknownId).append(" | — |\n");
        }
        return out.toString();
    }

    private static String format(Map<String, Integer> tally) {
        if (tally.isEmpty()) {
            return "—";
        }
        StringBuilder out = new StringBuilder();
        new TreeMap<>(tally).forEach((key, n) -> out.append(out.isEmpty() ? "" : " · ").append(key).append(' ').append(n));
        return out.toString();
    }
}
