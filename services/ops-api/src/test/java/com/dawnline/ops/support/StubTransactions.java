package com.dawnline.ops.support;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 커밋을 실패시킬 수 있는 트랜잭션 관리자 — 「카운터는 커밋 뒤에 센다」를 DB 없이 보려고 둔다
 * (CLAUDE.md). {@code libs/messaging} 의 같은 이름 도구는 그 모듈의 테스트 소스라 가져올 수 없다.
 */
public final class StubTransactions implements PlatformTransactionManager {

    private final boolean failOnCommit;

    private StubTransactions(boolean failOnCommit) {
        this.failOnCommit = failOnCommit;
    }

    /** 늘 커밋에 성공한다. */
    public static StubTransactions committing() {
        return new StubTransactions(false);
    }

    /** 커밋에서 실패한다. */
    public static StubTransactions failingOnCommit() {
        return new StubTransactions(true);
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus(true);
    }

    @Override
    public void commit(TransactionStatus status) {
        if (failOnCommit) {
            throw new IllegalStateException("커밋 실패 (테스트)");
        }
    }

    @Override
    public void rollback(TransactionStatus status) {
    }
}
