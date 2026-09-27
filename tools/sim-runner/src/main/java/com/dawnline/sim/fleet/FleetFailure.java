package com.dawnline.sim.fleet;

/**
 * 함대 단계가 도구 쪽 결함으로 멈췄다 — 전제 · 템플릿 없음 · 시간 예산 · 계획 실패 · 비활성화 (ADR-067 결정 8).
 *
 * <p>미배정 판정(§6.7)의 ✗ 는 여기 들지 않는다 — 그것은 기준에 대한 발견이고 실행의 결함이 아니다.
 */
public final class FleetFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message 무엇이 어긋났는가 — 다음에 볼 곳까지
     */
    public FleetFailure(String message) {
        super(message);
    }
}
