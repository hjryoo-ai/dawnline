package com.dawnline.fulfillment.domain;

/**
 * 서비스 티어 (DESIGN.md §2.2).
 *
 * <h2>왜 order-service 의 것을 쓰지 않는가</h2>
 * 서비스 간 소스 의존은 금지다(불변규칙 3, ArchUnit 규칙 3). 두 서비스가 같은 어휘를 각자
 * 정의하고, <strong>공유되는 진실은 이벤트 계약</strong>이다
 * ({@code contracts/events/*.schema.json} 의 {@code serviceTier} enum).
 *
 * <p>중복이므로 어긋날 수 있다. 그래서 {@code ServiceTierContractTest} 가 이 enum 의 값 집합이
 * 계약 파일의 enum 과 같은지 검사한다 — 어느 한쪽만 고치면 그 자리에서 깨진다.
 *
 * <p>order-service 의 {@code ServiceTier} 와 달리 컷오프·배송창 계산을 담지 않는다. 접수 시점의
 * 컷오프는 order-service 가 정하고(ADR-020) 이 서비스는 {@code order.placed} 가 싣고 온
 * {@code cutoffAt} 을 그대로 쓴다.
 *
 * <p><strong>예외는 약속 개정 경로 하나다.</strong> grace 를 넘겨 도착한 주문을 다음 웨이브로
 * 보내려면 <em>다음</em> 컷오프를 알아야 하는데, 그때는 §2.2 표를 여기에 다시 적지 않고
 * {@code libs/common} 의 {@code CutoffSchedule} 을 부른다 — 표의 복사본을 만들지 않으면서 물을
 * 수 있는 유일한 방법이다(ADR-020 후속 정정 2). 티어를 {@code name()} 으로 넘기는 이유는 공유되는
 * 진실이 이벤트 계약의 enum <em>값</em>이기 때문이다.
 */
public enum ServiceTier {

    /** 새벽 배송. */
    DAWN,

    /** 당일 배송. */
    SAME_DAY,

    /** 익일 배송. */
    NEXT_DAY
}
