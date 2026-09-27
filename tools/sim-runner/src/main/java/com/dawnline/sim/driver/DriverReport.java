package com.dawnline.sim.driver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 기사 시뮬레이션 결과. {@code ScenarioReport} 와 같은 모양이고 같은 이유로 존재한다 —
 * 실패한 스캔이 <em>왜</em> 실패했는지가 없으면 다음에 볼 곳을 못 정한다.
 *
 * @param scenario        시나리오 이름
 * @param expectedRoutes  기다린 라우트 수
 * @param routes          실제로 받은 라우트 수
 * @param revisions       받아들인 개정 수 (최초 확정 포함)
 * @param staleRevisions  이미 본 개정이라 버린 수. <strong>실패가 아니다</strong> —
 *                        재전달·순서 역전을 흡수한 횟수다
 * @param completedRoutes 끝까지 돈 라우트 수
 * @param abandonedRoutes 재시도 · 재전송 상한을 넘겨 포기한 라우트 수
 * @param scans           보낸 요청 수 — 재시도 · 재전송을 포함한다(시도마다 하나). 전에는 「재시도 제외」라고 적혀 있었지만 코드는
 *                        처음부터 시도를 셌다({@code DriverTripTest} 「상한을 넘기면 그 라우트를 포기한다」가 4 를 본다)
 * @param retries         404 · 409 {@code shipment-contended} 를 받아 다시 보낸 횟수
 * @param resent          응답을 받지 못해(연결 거부 · 타임아웃) 다시 보낸 횟수 — 스캔은 서버에서 멱등이라 다시 보낸다
 * @param outcomes        {@code orders[].outcome} 별 건수
 * @param failures        실패 사유별 스캔 수
 */
public record DriverReport(
        String scenario,
        int expectedRoutes,
        long routes,
        long revisions,
        long staleRevisions,
        long completedRoutes,
        long abandonedRoutes,
        long scans,
        long retries,
        long resent,
        Map<String, Long> outcomes,
        Map<String, Long> failures) {

    public DriverReport {
        outcomes = Map.copyOf(outcomes);
        failures = Map.copyOf(failures);
    }

    /**
     * 기대한 만큼의 라우트를 하나도 포기하지 않고 끝냈는가. 이 값이 종료 코드가 된다.
     *
     * <p>라우트가 <em>적게</em> 온 것도 실패다. 「기사가 0대 돌았는데 성공」이면 스크립트가
     * 성공이라고 말한 뒤 아무 일도 일어나지 않은 것이고, 그것은 {@code SmokeScenario} 가
     * 막으려던 것과 같은 모양이다.
     */
    public boolean isSuccess() {
        return completedRoutes >= expectedRoutes && abandonedRoutes == 0 && failures.isEmpty();
    }

    /** 사람이 읽는 요약. */
    public String toMarkdown() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("시나리오", scenario + " (기사)");
        rows.put("라우트", "%d / %d 기대".formatted(routes, expectedRoutes));
        rows.put("완주 / 포기", "%d / %d".formatted(completedRoutes, abandonedRoutes));
        rows.put("개정 반영 / 지난 개정", "%d / %d".formatted(revisions, staleRevisions));
        rows.put("보낸 요청 (재시도 · 재전송 포함)", String.valueOf(scans));
        rows.put("404 · 409 재시도", String.valueOf(retries));
        rows.put("전송 실패 재전송", String.valueOf(resent));

        StringBuilder out = new StringBuilder("| 항목 | 값 |\n|---|---|\n");
        rows.forEach((key, value) -> out.append("| ").append(key).append(" | ").append(value).append(" |\n"));
        new TreeMap<>(outcomes).forEach((outcome, count) ->
                out.append("| — outcome ").append(outcome).append(" | ").append(count).append(" |\n"));
        new TreeMap<>(failures).forEach((reason, count) ->
                out.append("| — 실패 ").append(reason).append(" | ").append(count).append(" |\n"));
        return out.toString();
    }
}
