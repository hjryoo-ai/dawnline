package com.dawnline.tracking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.tracking.application.port.in.RecordScanUseCase;
import com.dawnline.tracking.application.port.in.RecordScanUseCase.ScanCommand;
import com.dawnline.tracking.application.port.in.RecordScanUseCase.ScanResult;
import com.dawnline.tracking.domain.ScanType;
import com.dawnline.tracking.domain.TrackingErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * 스캔 재시도의 규칙 (DESIGN.md §5.4) — 무엇을 몇 번 다시 하는가. 실제 JPA 충돌 · 트랜잭션 · HTTP 는 {@code ScanContentionIT} 가 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ContendedScanRetryTest {

    private static final UUID ROUTE = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final ScanCommand COMMAND = new ScanCommand(ROUTE, 1, List.of(ORDER), ScanType.ARRIVED,
            Instant.parse("2026-09-27T15:00:00Z"), null, null, null);
    private static final ScanResult RESULT = new ScanResult(ROUTE, 1, ScanType.ARRIVED, COMMAND.occurredAt(), List.of());

    private final AtomicInteger calls = new AtomicInteger();

    /** 앞의 {@code failures} 번은 낙관적 락으로 지고 그 뒤로는 이긴다. */
    private RecordScanUseCase losing(int failures) {
        return command -> {
            if (calls.incrementAndGet() <= failures) {
                throw new OptimisticLockingFailureException("version moved");
            }
            return RESULT;
        };
    }

    @Test
    void 겹치지_않으면_한_번만_부른다() {
        assertThat(new ContendedScanRetry(losing(0)).record(COMMAND)).isSameAs(RESULT);
        assertThat(calls).hasValue(1);
    }

    @Test
    void 두_번_지고_세_번째에_이기면_그_결과다() {
        assertThat(new ContendedScanRetry(losing(ContendedScanRetry.ATTEMPTS - 1)).record(COMMAND)).isSameAs(RESULT);
        assertThat(calls).hasValue(ContendedScanRetry.ATTEMPTS);
    }

    @Test
    void 세_번_모두_지면_shipment_contended_이고_네_번째는_없다() {
        assertThatThrownBy(() -> new ContendedScanRetry(losing(Integer.MAX_VALUE)).record(COMMAND))
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.errorCode()).isEqualTo(TrackingErrorCode.SHIPMENT_CONTENDED);
                    assertThat(failure.details()).containsEntry("attempts", ContendedScanRetry.ATTEMPTS);
                    assertThat(failure.getCause()).as("마지막 실패를 잃지 않는다")
                            .isInstanceOf(OptimisticLockingFailureException.class);
                });
        assertThat(calls).hasValue(ContendedScanRetry.ATTEMPTS);
    }

    @Test
    void 겹침이_아닌_실패는_다시_하지_않는다() {
        // 404 · 400 · 상태 전이 409 는 다시 해도 같은 답이다 — 다시 하면 부하만 는다.
        RecordScanUseCase notFound = command -> {
            calls.incrementAndGet();
            throw NotFoundException.of("Shipment", List.of(ORDER));
        };

        assertThatThrownBy(() -> new ContendedScanRetry(notFound).record(COMMAND)).isInstanceOf(NotFoundException.class);
        assertThat(calls).hasValue(1);
    }
}
