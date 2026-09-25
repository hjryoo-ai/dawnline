package com.dawnline.messaging;

/**
 * 실패의 성질 — 발행 측과 소비 측이 같은 두 값으로 판정한다 (DESIGN.md §4.6, ADR-015 와 그 후속 정정).
 *
 * <p>원칙도 같다: <strong>애매하면 일시적이다.</strong> 결정적 판정은 사람에게 넘기는 행위이고(발행 측은 격리, 소비 측은 DLQ),
 * 잘못 넘기면 멀쩡한 이벤트가 사람 손을 기다린다. 잘못 기다리면 그 자리가 멈추고 — 그 멈춤은 알림이 보인다.
 */
public enum FailureKind {

    /** 재시도해도 같은 결과. 발행 측은 격리하고, 소비 측은 DLQ 로 보낸다. */
    DETERMINISTIC,

    /** 기다리면 풀린다. 발행 측은 다음 폴링에, 소비 측은 백오프 뒤에 다시 한다 — 끝없이. */
    TRANSIENT
}
