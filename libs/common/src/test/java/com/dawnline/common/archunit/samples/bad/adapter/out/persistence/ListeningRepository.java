package com.dawnline.common.archunit.samples.bad.adapter.out.persistence;

import org.springframework.kafka.annotation.KafkaListener;

/**
 * 규칙 4 위반 표본 — {@code @KafkaListener} 가 인바운드 어댑터 밖에 있다.
 *
 * <p>왜 위치가 문제인가: 리스너는 <strong>멱등 게이트를 지나야</strong> 한다(불변규칙 2).
 * {@code adapter.in.messaging} 밖에 리스너가 생기면 그 클래스는 리뷰에서 "메시징 코드" 로 읽히지
 * 않고, {@code processed_events} 체크 없이 곧바로 도메인을 건드리는 경로가 열린다.
 * at-least-once 배달에서 그것은 <em>같은 이벤트를 두 번 적용하는</em> 것이다(§8.5).
 *
 * <p>여기가 리포지토리라는 점도 의도적이다 — 실수는 보통 "이 데이터를 받아서 바로 저장하면 되는데"
 * 로 시작한다.
 */
public final class ListeningRepository {

    /**
     * @param message 받은 메시지
     */
    @KafkaListener(topics = "dawnline.order.placed.v1")
    public void onMessage(String message) {
        if (message == null) {
            throw new IllegalArgumentException("message");
        }
    }
}
