package com.dawnline.sim.fleet;

import com.dawnline.sim.config.SimProperties.Scenario.Fleet;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 리포트 머리 — 계산값과 실측을 나란히 (ADR-067 결정 8).
 *
 * <p>§6.7 의 「미배정 ≤ 0.5%」 판정은 표에 남고 종료 코드에 들지 않는다 — ✗ 는 기준에 대한 발견이다. 종료 코드가 말하는 것은
 * {@code failures} 다(도구 쪽 결함).
 *
 * @param mode        함대 단계
 * @param waves       창의 웨이브마다 한 줄
 * @param rows        캠프 × 조합 — 계산 부족분과 추가 대수
 * @param added       더한 차량
 * @param deactivated 비활성화한 차량
 * @param auditRows   받은 감사 행 id 수(전부, 거절 포함)
 * @param audit       캠프 × 커맨드 — 감사 행의 기대와 받은 수(ADR-067 결정 9 — 세는 것이 아니라 대조한다)
 * @param reassigns   조기 마감한 웨이브마다의 재배정 결과
 * @param failures    도구 쪽 결함 — 비어 있어야 성공이다
 */
public record FleetReport(Fleet mode, List<WaveLine> waves, List<Row> rows, int added, int deactivated, int auditRows,
        List<AuditLine> audit, List<Reassign> reassigns, List<String> failures) {

    /** §6.7 「미배정률 ≤ 0.5%(정상 용량)」 — 천분율 5. */
    static final long UNASSIGNED_PERMILLE_LIMIT = 5;

    private static final DateTimeFormatter KST = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"));

    public FleetReport {
        waves = List.copyOf(waves);
        rows = List.copyOf(rows);
        audit = List.copyOf(audit);
        reassigns = List.copyOf(reassigns);
        failures = List.copyOf(failures);
    }

    /** 운영자 커맨드 — ops-api 의 {@code audit_logs.action} 가운데 함대 단계가 보내는 것. */
    public enum Action {
        ADD_VEHICLE, CLOSE_WAVE, REASSIGN_STOP, DEACTIVATE_VEHICLE
    }

    /**
     * 캠프 × 커맨드의 감사 행 대조 한 줄.
     *
     * @param campId    캠프
     * @param action    커맨드
     * @param expected  기대 — 증차는 판정의 부족분 합, 조기 마감 · 재배정은 1(옮길 곳이 없으면 0), 비활성화는 성공 수의 기대(더한 대수)
     * @param received  받은 감사 id 수 — 비활성화는 409 재시도를 포함한 시도 전부
     * @param succeeded 비활성화의 성공 수 — 다른 커맨드는 {@code null}
     */
    public record AuditLine(UUID campId, Action action, int expected, int received, @Nullable Integer succeeded) {

        /** @return 기대와 같다 — 비활성화는 성공 수로 본다 */
        public boolean matches() {
            return (succeeded == null ? received : succeeded) == expected;
        }
    }

    /**
     * 재배정 한 번.
     *
     * @param campId  캠프
     * @param waveId  웨이브
     * @param status  HTTP 상태 — 보내지 않았으면 {@code null}
     * @param code    거절의 코드
     * @param skipped 보내지 않은 이유
     */
    public record Reassign(UUID campId, UUID waveId, @Nullable Integer status, @Nullable String code,
            @Nullable String skipped) {

        String describe() {
            if (skipped != null) {
                return "보내지 않음 — " + skipped;
            }
            if (status != null && status >= 200 && status < 300) {
                return "옮겼다";
            }
            return "%s %s%s".formatted(status, code, "stop-not-planned".equals(code) ? " — 기사가 먼저 닿았다(늦었다)" : "");
        }
    }

    /** @return 도구 쪽 결함이 없다 */
    public boolean isSuccess() {
        return failures.isEmpty();
    }

    /**
     * 웨이브 한 줄.
     *
     * @param campId        캠프
     * @param waveId        웨이브
     * @param candidates    계산의 기준 후보 수
     * @param orders        마감 때의 주문 수(읽기 모델 집계)
     * @param unassigned    계획 뒤 미배정
     * @param routes        라우트 수
     * @param added         더한 차량
     * @param provisionedAt 증차 완료 시각(주입 시계)
     * @param closedAt      마감 시각({@code wave.closed})
     */
    public record WaveLine(@Nullable UUID campId, UUID waveId, @Nullable Integer candidates, @Nullable Integer orders,
            @Nullable Integer unassigned, @Nullable Integer routes, int added, @Nullable Instant provisionedAt,
            @Nullable Instant closedAt) {
    }

    /**
     * 캠프 × 조합 한 줄.
     *
     * @param campId    캠프
     * @param label     조합
     * @param status    판정
     * @param shortfall 계산 부족분 — {@code NO_TEMPLATE} 이면 {@code null}
     * @param added     더한 대수
     */
    public record Row(UUID campId, String label, String status, @Nullable Integer shortfall, int added) {
    }

    /** 사람이 읽는 머리. */
    public String toMarkdown() {
        StringBuilder out = new StringBuilder("| 함대 | 값 |\n|---|---|\n");
        line(out, "함대 단계", mode == Fleet.FEASIBLE ? "feasible — 기준(80%)이 낸 만큼 증차" : "as-is — 증차 없음");
        line(out, "창의 DAWN 웨이브", String.valueOf(waves.size()));
        line(out, "더한 차량 / 비활성화", "%d / %d".formatted(added, deactivated));
        line(out, "감사 행(받은 X-Dawnline-Audit-Id)", String.valueOf(auditRows));
        line(out, "계산의 기준 후보 / 마감 주문", "%s / %s".formatted(sum(waves, WaveLine::candidates), sum(waves, WaveLine::orders)));
        line(out, "계획 뒤 미배정", unassigned());
        line(out, "시간 예산(증차 완료 < 마감)", budget());
        for (String failure : failures) {
            line(out, "✗ 실패", failure);
        }

        if (!audit.isEmpty()) {
            out.append("\n| 캠프 | 커맨드 | 기대 | 받은 감사 id | 대조 |\n|---|---|---:|---:|---|\n");
            for (AuditLine row : audit) {
                out.append("| %s | %s | %d | %s | %s |\n".formatted(PeakFleet.short8(row.campId()), row.action(),
                        row.expected(), row.succeeded() == null ? String.valueOf(row.received())
                                : "%d (성공 %d)".formatted(row.received(), row.succeeded()),
                        row.matches() ? "✅" : "✗"));
            }
        }
        for (Reassign reassign : reassigns) {
            line(out, "재배정 " + PeakFleet.short8(reassign.waveId()), reassign.describe());
        }

        List<Row> interesting = rows.stream()
                .filter(row -> !"FEASIBLE".equals(row.status()) || row.added() > 0).toList();
        out.append("\n| 캠프 | 조합 | 판정 | 계산 부족분 | 추가 |\n|---|---|---|---:|---:|\n");
        if (interesting.isEmpty()) {
            out.append("| — | 모든 조합이 여유 안 | FEASIBLE | 0 | 0 |\n");
        }
        for (Row row : interesting) {
            out.append("| %s | %s | %s | %s | %d |\n".formatted(PeakFleet.short8(row.campId()), row.label(), row.status(),
                    row.shortfall() == null ? "모름" : row.shortfall(), row.added()));
        }
        return out.toString();
    }

    private String unassigned() {
        Long unassigned = sum(waves, WaveLine::unassigned);
        Long orders = sum(waves, WaveLine::orders);
        if (unassigned == null || orders == null || orders == 0) {
            return "모름 — 계획이 끝나지 않았거나 읽기 모델에 칸이 없다";
        }
        boolean within = unassigned * 1000 <= orders * UNASSIGNED_PERMILLE_LIMIT;
        return "%d / %d = %.2f%% — §6.7 ≤ 0.5%% %s%s".formatted(unassigned, orders, 100.0 * unassigned / orders,
                within ? "✅" : "✗", within ? "" : " (기준에 대한 발견 — 종료 코드에 넣지 않는다, ADR-067 결정 8)");
    }

    private String budget() {
        List<WaveLine> provisioned = waves.stream().filter(w -> w.added() > 0).toList();
        if (provisioned.isEmpty()) {
            return "— 더한 차량이 없다";
        }
        Instant latestDone = provisioned.stream().map(WaveLine::provisionedAt).filter(java.util.Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
        Instant earliestClose = provisioned.stream().map(WaveLine::closedAt).filter(java.util.Objects::nonNull)
                .min(Instant::compareTo).orElse(null);
        if (latestDone == null || earliestClose == null) {
            return "모름";
        }
        return "증차 완료 %s KST · 가장 이른 마감 %s KST %s".formatted(KST.format(latestDone), KST.format(earliestClose),
                latestDone.isBefore(earliestClose) ? "✅" : "✗");
    }

    private static @Nullable Long sum(List<WaveLine> waves, java.util.function.Function<WaveLine, @Nullable Integer> field) {
        long total = 0;
        for (WaveLine wave : waves) {
            Integer value = field.apply(wave);
            if (value == null) {
                return null;
            }
            total += value;
        }
        return waves.isEmpty() ? null : total;
    }

    private static void line(StringBuilder out, String key, String value) {
        out.append("| ").append(key).append(" | ").append(value).append(" |\n");
    }
}
