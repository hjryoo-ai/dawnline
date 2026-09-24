package com.dawnline.ops.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;

/**
 * 씨앗 고정 셔플 (ADR-051 결정 6 의 2 — 불변규칙 12). 같은 씨앗이면 같은 순서다 — 깨졌을 때
 * 실패 메시지의 씨앗 하나로 그 순서를 다시 만들 수 있어야 한다.
 */
public final class Shuffles {

    private Shuffles() {
    }

    /**
     * @param causal 인과 순서
     * @param seed   씨앗
     * @param <T>    원소
     * @return 뒤섞은 사본
     */
    public static <T> List<T> of(List<T> causal, long seed) {
        List<T> copy = new ArrayList<>(causal);
        Collections.shuffle(copy, new SplittableRandom(seed));
        return copy;
    }

    /**
     * DLQ 재처리의 순서 (DESIGN.md §4.6 「DLQ 재처리」, ADR-053 결정 5). 사건 하나가 DLQ 로 빠지고(그 처리는
     * 롤백됐다) 나머지가 인과 순서로 흐른 뒤, 재발행된 그 사건이 파티션 끝에 붙어 맨 뒤에 온다.
     *
     * <p>씨앗 셔플은 이 모양을 거의 만들지 않는다 — 「나머지는 인과 순서」가 우연히 나올 확률은 없는 것과 같다.
     * 그리고 씨 25회로는 31개 사건 중 18개만 한 번이라도 맨 끝에 왔다(2026-09-24 측정). 그래서 이 모양은 따로,
     * 모든 사건에 대해 돈다.
     *
     * @param causal 인과 순서
     * @param index  DLQ 로 빠졌다가 재처리되는 사건
     * @param <T>    원소
     * @return 그 사건만 맨 끝으로 옮긴 사본
     */
    public static <T> List<T> replayedLast(List<T> causal, int index) {
        List<T> copy = new ArrayList<>(causal);
        copy.add(copy.remove(index));
        return copy;
    }
}
