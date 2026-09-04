package com.dawnline.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dawnline.order.application.port.out.IdempotencyRecords;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 멱등 기록 보존 정리 (ADR-019).
 *
 * <p>실제 삭제는 {@code IdempotencyCleanupIT} 가 실물 DB 로 본다. 여기서 보는 것은 <em>배치 루프의
 * 규칙</em>이다 — 언제 멈추는가, 상한에 걸리면 어떻게 되는가, 배치마다 트랜잭션을 여는가.
 * 마지막 것은 이 클래스의 존재 이유이면서(락 시간 + 인덱스 효과, ADR-019 §5) 눈으로는 확인되지 않는다.
 */
@DisplayName("IdempotencyKeyCleaner — 배치 루프")
class IdempotencyKeyCleanerTest {

    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private static final int BATCH = 100;

    private IdempotencyRecords records;
    private PlatformTransactionManager transactionManager;
    private IdempotencyKeyCleaner cleaner;

    @BeforeEach
    void setUp() {
        records = mock(IdempotencyRecords.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        cleaner = new IdempotencyKeyCleaner(records, transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC), BATCH, 3);
    }

    @Test
    void 배치가_덜_차면_거기서_멈춘다() {
        // limit 만큼 못 채웠다는 것이 "대상이 소진됐다" 는 신호다.
        when(records.deleteExpired(NOW, BATCH)).thenReturn(BATCH, 7);

        assertThat(cleaner.deleteExpired()).isEqualTo(BATCH + 7);
        verify(records, times(2)).deleteExpired(NOW, BATCH);
    }

    @Test
    void 지울_것이_없으면_한_번만_보고_끝낸다() {
        when(records.deleteExpired(NOW, BATCH)).thenReturn(0);

        assertThat(cleaner.deleteExpired()).isZero();
        verify(records, times(1)).deleteExpired(NOW, BATCH);
    }

    @Test
    void 한_실행의_배치_수에_상한이_있다() {
        // 상한이 없으면 밀린 정리가 한 번의 실행을 몇 분씩 잡아 둘 수 있다.
        when(records.deleteExpired(NOW, BATCH)).thenReturn(BATCH);

        assertThat(cleaner.deleteExpired()).isEqualTo(BATCH * 3);
        verify(records, times(3)).deleteExpired(NOW, BATCH);
    }

    @Test
    void 배치마다_트랜잭션을_연다() {
        // 하나의 트랜잭션으로 묶으면 배치로 나눈 의미가 사라진다. 락도 오래 잡고,
        // 커밋 전에는 지운 인덱스 항목을 죽은 것으로 표시할 수 없어 인덱스 효과도 사라진다
        // (ADR-019 §5 의 측정: 하루치 0.47s → 11.29s).
        when(records.deleteExpired(NOW, BATCH)).thenReturn(BATCH, BATCH, 0);

        cleaner.deleteExpired();

        verify(transactionManager, times(3)).getTransaction(any());
        verify(transactionManager, times(3)).commit(any(TransactionStatus.class));
    }

    @Test
    void 기준_시각은_주입된_시계에서_온다() {
        when(records.deleteExpired(any(), anyInt())).thenReturn(0);

        cleaner.deleteExpired();

        verify(records).deleteExpired(eq(NOW), anyInt());
    }

    @Test
    void 스케줄_실행은_예외를_삼킨다() {
        // 정리 실패는 용량 문제지 정확성 문제가 아니다. 다음 실행이 이어받는다.
        when(records.deleteExpired(any(), anyInt())).thenThrow(new IllegalStateException("DB 불가"));

        assertThatCode(() -> cleaner.cleanupExpired()).doesNotThrowAnyException();
    }

    @Test
    void 직접_호출은_예외를_그대로_올린다() {
        // 운영자가 수동으로 돌릴 때는 실패를 알아야 한다.
        when(records.deleteExpired(any(), anyInt())).thenThrow(new IllegalStateException("DB 불가"));

        assertThatThrownBy(() -> cleaner.deleteExpired()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 잘못된_인자는_생성에서_거부한다() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThatThrownBy(() -> new IdempotencyKeyCleaner(null, transactionManager, clock, 1, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IdempotencyKeyCleaner(records, null, clock, 1, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IdempotencyKeyCleaner(records, transactionManager, clock, 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
        assertThatThrownBy(() -> new IdempotencyKeyCleaner(records, transactionManager, clock, 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBatchesPerRun");
    }
}
