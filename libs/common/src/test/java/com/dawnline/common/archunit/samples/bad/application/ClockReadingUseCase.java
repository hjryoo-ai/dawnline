package com.dawnline.common.archunit.samples.bad.application;

import java.time.Instant;

/**
 * 규칙 7 위반 표본 — 유스케이스가 시스템 시계를 직접 읽는다 (CLAUDE.md 불변규칙 12).
 *
 * <p>이 한 줄이 왜 결함인지는 Phase 1에서 실제로 드러났다. {@link Instant#now()} 의 해상도는
 * 플랫폼에 달려 있다 — macOS 는 마이크로초에서 끊기고 Linux 는 나노초까지 준다. PostgreSQL 의
 * {@code TIMESTAMPTZ} 는 마이크로초까지만 담으므로, 이렇게 만든 시각을 응답에 실으면
 * <strong>API 가 DB 에 저장할 수 없는 값을 돌려주게 된다.</strong> {@code POST} 응답의 시각과
 * {@code GET} 의 시각이 다르고, 멱등 재생은 "그때 준 답" 을 그대로 주지 못한다.
 *
 * <p>그리고 그 결함은 개발 기계에서는 <em>보이지 않는다</em>. 통합 테스트가 CI 에서만 깨졌고,
 * 그때서야 원인을 찾았다. 사람의 리뷰로는 이 한 줄이 눈에 띄지 않는다 — 그래서 규칙이 필요하다.
 */
public class ClockReadingUseCase {

    /**
     * @return 지금 시각 — 주입받지 않고 직접 읽는 것이 위반이다
     */
    public Instant placedAt() {
        return Instant.now();
    }
}
