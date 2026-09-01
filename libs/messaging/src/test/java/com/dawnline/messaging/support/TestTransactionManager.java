package com.dawnline.messaging.support;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 커밋·롤백 횟수만 세는 트랜잭션 관리자.
 *
 * <p>{@code IdempotentConsumer} 는 "거부는 커밋하고, 그 외 예외는 롤백한다" 는 규칙이 정확성의 핵심이라
 * (§4.6) 그 분기를 DB 없이 검증할 수 있어야 한다.
 */
public final class TestTransactionManager implements PlatformTransactionManager {

    private int commits;
    private int rollbacks;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus(true);
    }

    @Override
    public void commit(TransactionStatus status) {
        commits++;
    }

    @Override
    public void rollback(TransactionStatus status) {
        rollbacks++;
    }

    /** 커밋 횟수. */
    public int commits() {
        return commits;
    }

    /** 롤백 횟수. */
    public int rollbacks() {
        return rollbacks;
    }
}
