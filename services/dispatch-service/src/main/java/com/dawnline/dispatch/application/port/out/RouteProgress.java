package com.dawnline.dispatch.application.port.out;

import org.jspecify.annotations.Nullable;

/**
 * 라우트가 어디까지 갔는가 — {@code route:{id}:progress} 의 세 칸 (DESIGN.md §7.2).
 *
 * <p>§6.8 의 부분 재계획이 「미완료 stop 만」 다시 푸는 근거이고, 그 첫 소비자는 Phase 5-3 이다.
 * 5-5 는 채우는 쪽과 폴백을 만든다.
 *
 * @param nextSeq   아직 <strong>종결되지 않은</strong> stop 중 가장 작은 {@code seq}. 취소된 stop 은
 *                  방문하지 않으므로 세지 않는다. 남은 stop 이 없으면 {@code null} —
 *                  <em>0</em> 을 쓰지 않는 이유는 {@code seq} 가 1부터라 0 이 「없음」처럼 보이지만
 *                  실제로는 값처럼 읽히기 때문이다(부재는 값이 아니다)
 * @param completed {@code COMPLETED} 인 stop 수
 * @param failed    {@code FAILED} 인 stop 수
 */
public record RouteProgress(@Nullable Integer nextSeq, int completed, int failed) {

    public RouteProgress {
        if (nextSeq != null && nextSeq < 1) {
            throw new IllegalArgumentException("seq 는 1부터입니다: " + nextSeq);
        }
        if (completed < 0 || failed < 0) {
            throw new IllegalArgumentException(
                    "음수 집계: completed=" + completed + " failed=" + failed);
        }
    }

    /** 남은 stop 이 없는가 — 라우트가 끝났다는 뜻이다. */
    public boolean done() {
        return nextSeq == null;
    }
}
