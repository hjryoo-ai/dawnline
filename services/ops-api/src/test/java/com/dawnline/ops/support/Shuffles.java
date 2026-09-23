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
}
