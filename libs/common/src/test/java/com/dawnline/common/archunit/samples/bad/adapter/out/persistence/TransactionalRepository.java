package com.dawnline.common.archunit.samples.bad.adapter.out.persistence;

import org.springframework.transaction.annotation.Transactional;

/**
 * 규칙 5 위반 표본 — 아웃바운드 어댑터가 {@code @Transactional} 로 트랜잭션 경계를 정한다.
 *
 * <p>이것이 왜 위반인지는 outbox 를 생각하면 분명하다(DESIGN.md §4.4, 불변규칙 1).
 * 트랜잭션이 리포지토리 메서드마다 열리고 닫히면, 주문 INSERT 와 outbox INSERT 가
 * <strong>서로 다른 트랜잭션</strong>이 된다. 둘 사이에서 프로세스가 죽으면 "주문은 저장됐는데
 * 이벤트는 없는" 상태가 남고, 그것은 outbox 설계가 애초에 불가능하게 만들려던 상태다.
 *
 * <p>어댑터에 붙은 {@code @Transactional} 은 눈에 잘 띄지 않는다 — 컴파일도 되고, 단위 테스트도
 * 통과하고, 통합 테스트조차 실패를 재현하려면 정확한 순간에 프로세스를 죽여야 한다.
 * 그래서 이 규칙이 자동으로 잡아야 한다.
 */
public final class TransactionalRepository {

    /**
     * @param id 저장할 식별자
     */
    @Transactional
    public void save(String id) {
        // 실제 저장은 하지 않는다. 어노테이션의 위치만이 이 표본의 내용이다.
        if (id == null) {
            throw new IllegalArgumentException("id");
        }
    }
}
