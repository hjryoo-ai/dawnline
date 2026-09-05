/**
 * JDBC 로 직접 하는 일 — 지금은 릴레이 리더 락 하나다.
 *
 * <p>여기 있는 코드는 JPA 도 스프링 데이터도 거치지 않는다. advisory lock 은 <strong>세션</strong>에
 * 걸리므로 커넥션을 직접 들고 있어야 하고, 그것을 추상화 뒤에 숨기면 "어느 커넥션인가" 라는 이
 * 락의 유일한 어려운 질문이 보이지 않게 된다(ADR-027 후속 정정).
 */
package com.dawnline.messaging.jdbc;
