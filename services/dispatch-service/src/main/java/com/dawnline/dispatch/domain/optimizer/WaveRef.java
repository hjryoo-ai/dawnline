package com.dawnline.dispatch.domain.optimizer;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 계획 대상 웨이브의 참조 (DESIGN.md §6.2).
 *
 * <p>순수 함수가 웨이브 애그리거트를 알 필요는 없다 — 계획에 필요한 것은 식별자와, 설명·로그에
 * 쓸 최소한의 맥락뿐이다. 티어를 문자열로 받는 이유는 {@code TierSchedule} 과 같다: 서비스 간
 * 공유되는 진실은 이벤트 계약의 enum 값이다.
 *
 * @param waveId      웨이브 id
 * @param campId      캠프 id
 * @param serviceTier 티어 이름 (계약의 enum 값)
 * @param cutoffAt    컷오프 시각
 */
public record WaveRef(UUID waveId, UUID campId, String serviceTier, Instant cutoffAt) {

    public WaveRef {
        Objects.requireNonNull(waveId, "waveId");
        Objects.requireNonNull(campId, "campId");
        Objects.requireNonNull(serviceTier, "serviceTier");
        Objects.requireNonNull(cutoffAt, "cutoffAt");
    }
}
