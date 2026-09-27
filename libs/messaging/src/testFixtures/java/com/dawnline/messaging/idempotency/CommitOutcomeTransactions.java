package com.dawnline.messaging.idempotency;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 커밋을 성공시키거나 실패시키는 트랜잭션 관리자 — 「카운터는 커밋 뒤에 센다」(CLAUDE.md)를 DB 없이 보려고 둔다.
 *
 * <p>리스너의 카운터가 {@link IdempotentConsumer} 의 콜백 <em>안</em>에 있으면 커밋 전이다 — 이 관리자로 커밋을 실패시키고
 * 카운터가 0 인지 본다. ops-api 에도 같은 모양이 테스트 소스에 있다({@code StubTransactions}) — 이것이 그 뒤에 온 공용판이다.
 */
public final class CommitOutcomeTransactions implements PlatformTransactionManager {

    private final boolean failOnCommit;

    private CommitOutcomeTransactions(boolean failOnCommit) {
        this.failOnCommit = failOnCommit;
    }

    /** @return 늘 커밋에 성공한다 */
    public static CommitOutcomeTransactions committing() {
        return new CommitOutcomeTransactions(false);
    }

    /** @return 커밋에서 실패한다 — 콜백은 끝까지 돈다 */
    public static CommitOutcomeTransactions failingOnCommit() {
        return new CommitOutcomeTransactions(true);
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
        // 되돌릴 것이 없다.
    }
}
