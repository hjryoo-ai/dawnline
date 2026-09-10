package com.dawnline.dispatch.domain;

/**
 * 이 계획이 <strong>왜</strong> 그 모드로 돌았는가 (DESIGN.md §6.7, [ADR-034]).
 *
 * <p>{@code route_plans.mode_reason} 에 남는다. 카운터 라벨은 <em>집계</em>지 개별 답이 아니고,
 * "이 웨이브는 왜 FAST 였나" 는 §6.3 의 설명 가능성과 같은 요구다 — 라우트에 "왜 이 차인가" 를
 * 남기면서 계획에 "왜 이 모드인가" 를 남기지 않을 이유가 없다.
 *
 * <h2>모름은 아니오가 아니다</h2>
 * {@link #LAG_UNKNOWN} 이 {@link #NONE} 과 따로 있는 이유다. 랙을 보지 못한 채 내린 FULL 은
 * "두 조건을 다 보고 아니었다" 와 다르고, 그 둘을 한 값으로 접으면 <strong>판단이 조용히
 * 멈춘 것</strong>이 정상과 구별되지 않는다 — ADR-027 이 리더 락에 세 상태를 둔 것과 같은 규칙이다.
 */
public enum PlanModeReason {

    /**
     * 운영자가 모드를 지정했다. 자동 판단은 돌지 않았다.
     *
     * <p>그래서 <strong>열화로 세지 않는다</strong> — 사람이 고른 것은 시스템이 포기한 것이 아니다.
     */
    REQUESTED,

    /**
     * 컨슈머 랙이 임계를 넘었다 (§6.7 첫 조건) — <strong>처리량이 모자란다.</strong>
     *
     * <p><strong>사다리의 윗단.</strong> 처방은 개선 단계를 <em>끄는</em> 것(FAST)이고,
     * 처리량 부족을 말하는 신호는 이것 하나뿐이다. 선행 지표라 버스트를 그 순간에 본다.
     */
    LAG,

    /**
     * 직전 계획이 예산의 비율을 넘겼다 — <strong>개선 단계가 예산을 다 썼다.</strong>
     *
     * <p><strong>사다리의 아랫단</strong>(2026-09-10 정정). 모드는 {@code FULL} 로 남고
     * 다음 계획의 <em>개선 예산만</em> 줄인다. 처음에는 이 사유도 FAST 를 냈는데, 측정이
     * 그것을 뒤집었다 — 예산으로 개선을 끊는 대가가 <strong>+0.21%</strong> 인데 FAST 의
     * 대가는 <strong>+9.4%</strong> 였다. <em>45배 비싼 처방</em>이다.
     *
     * <p>ADR-032 의 패스 단위 예산이 계획 시간을 이미 상한으로 묶으므로, 예산을 다 썼다는
     * 것은 과부하가 아니라 «개선이 아직 배가 고프다» 는 뜻이다. 후행 지표이기도 하다 —
     * 한 계획이 이미 늦은 뒤에야 켜진다.
     */
    BUDGET,

    /** 랙을 알 수 없었다. 열화 사유는 아니지만 {@link #NONE} 도 아니다. */
    LAG_UNKNOWN,

    /** 두 조건을 다 보았고 둘 다 아니었다. */
    NONE;

    /**
     * 이것이 <strong>열화</strong>인가 — 시스템이 밀려서 품질을 낮춘 것인가.
     *
     * <p>{@code dawnline_plan_degraded_total} 이 세는 것이 이 둘뿐이고, {@code reason} 라벨이
     * <strong>사다리의 어느 단</strong>인지를 말한다. {@link #BUDGET} 은 «덜 개선했다»,
     * {@link #LAG} 는 «개선하지 않았다» — 대가가 한 자릿수 배 차이라 한 수로 합치지 않는다.
     */
    public boolean isDegraded() {
        return this == LAG || this == BUDGET;
    }
}
