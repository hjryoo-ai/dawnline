package com.dawnline.fulfillment.domain;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * FC 선택의 결과 (DESIGN.md §5.2 1~6단계).
 *
 * <p>{@code sealed interface} + 패턴 매칭이다(CLAUDE.md 코딩 컨벤션). 성공과 실패를 한 record 의
 * nullable 필드로 표현하면 호출부가 "fc 가 null 이면 reason 을 본다" 같은 규약을 외워야 한다.
 */
public sealed interface FcSelectionResult {

    /**
     * FC 를 골랐다.
     *
     * @param fc             선택된 FC
     * @param fallbackReason 홈 FC 가 아니라 대체 FC 를 고른 이유. 홈 FC 를 그대로 쓴 경우 {@code null}
     */
    record Selected(CandidateFc fc, @Nullable FcFallbackReason fallbackReason) implements FcSelectionResult {

        public Selected {
            Objects.requireNonNull(fc, "fc");
        }

        /** 홈 FC 가 아니라 대체 FC 를 골랐는가. */
        public boolean isFallback() {
            return fallbackReason != null;
        }
    }

    /**
     * 배차할 수 없다.
     *
     * @param reason 사유
     */
    record Unserviceable(UnserviceableReason reason) implements FcSelectionResult {

        public Unserviceable {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** 성공했으면 선택된 FC. */
    default Optional<CandidateFc> selectedFc() {
        return this instanceof Selected selected ? Optional.of(selected.fc()) : Optional.empty();
    }

    /** 실패했으면 사유. */
    default Optional<UnserviceableReason> unserviceableReason() {
        return this instanceof Unserviceable unserviceable
                ? Optional.of(unserviceable.reason())
                : Optional.empty();
    }
}
