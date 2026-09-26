package com.dawnline.ops.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.ValidationException;
import com.dawnline.observability.DawnlineMetrics;
import com.dawnline.ops.application.port.in.ResolveAuditUseCase;
import com.dawnline.ops.application.port.out.AuditLog;
import com.dawnline.ops.domain.AuditErrorCode;
import com.dawnline.ops.domain.AuditResolution;
import com.dawnline.ops.domain.AuditResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 감사 해소 — 대상은 고치지 않고 행을 더한다, 한 트랜잭션, 거절도 행, 세기는 커밋 뒤 (DESIGN.md §5.5 「감사 해소」, ADR-065).
 *
 * <p>순서(잠금 → 판정 → 삽입 → 커밋 → 세기)는 읽어서는 보이지 않으므로 가짜 둘이 한 일지에 적고 테스트가 그것을 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ResolveAuditService — 감사 해소는 칸이 아니라 행이다")
class ResolveAuditServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-26T05:00:00Z");
    private static final UUID TARGET = UUID.fromString("0199a000-0000-7000-8000-0000000000d1");
    private static final String REASON = "그 시각 dawnline_fulfillment 는 NOLOGIN 이었다(chaos-db)";

    private final List<String> journal = new ArrayList<>();
    private final FakeAudit audit = new FakeAudit();
    private final FakeTransactions transactions = new FakeTransactions();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ResolveAuditService service =
            new ResolveAuditService(audit, transactions, Clock.fixed(NOW, ZoneOffset.UTC), registry);

    @Test
    void 만들면_RESOLVE_AUDIT_의_결과_넷이_0_으로_이미_있다() {
        for (String result : List.of("SUCCEEDED", "REJECTED", "FAILED", "UNKNOWN")) {
            assertThat(counted(result)).as(result).isZero();
        }
    }

    @Test
    void UNKNOWN_은_해소_행을_더하고_대상은_그대로다() {
        audit.rows.put(TARGET, row(AuditResult.UNKNOWN, NOW.minus(Duration.ofHours(1))));

        ResolveAuditUseCase.Resolved resolved = service.resolve("kim", TARGET, AuditResolution.NOT_APPLIED, REASON);

        assertThat(journal).containsExactly("begin", "lock", "findResolution", "record SUCCEEDED", "commit");
        assertThat(audit.recorded).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo(resolved.auditId());
            assertThat(entry.action()).isEqualTo("RESOLVE_AUDIT");
            assertThat(entry.targetType()).isEqualTo("AUDIT");
            assertThat(entry.targetId()).isEqualTo(TARGET);
            assertThat(entry.actor()).isEqualTo("kim");
            assertThat(entry.request()).as("인자만 — 판정과 근거").containsExactlyInAnyOrderEntriesOf(
                    Map.of("resolution", "NOT_APPLIED", "reason", REASON));
        });
        assertThat(audit.rows.get(TARGET).result()).as("대상 행은 고치지 않는다").isEqualTo(AuditResult.UNKNOWN);
        assertThat(counted("SUCCEEDED")).isEqualTo(1.0);
        assertThat(resolved.targetAuditId()).isEqualTo(TARGET);
    }

    @Test
    void PENDING_은_5분이_넘어야_해소할_수_있다() {
        UUID fresh = UUID.fromString("0199a000-0000-7000-8000-0000000000d2");
        audit.rows.put(fresh, row(AuditResult.PENDING, NOW.minus(Duration.ofMinutes(5))));
        audit.rows.put(TARGET, row(AuditResult.PENDING, NOW.minus(Duration.ofMinutes(5)).minusSeconds(1)));

        assertThatThrownBy(() -> service.resolve("kim", fresh, AuditResolution.APPLIED, REASON))
                .as("아직 도는 중일 수 있다 — 딱 5분은 아니다")
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(AuditErrorCode.NOT_RESOLVABLE);
                    assertThat(e.details()).containsEntry("currentResult", "PENDING");
                });
        assertThat(service.resolve("kim", TARGET, AuditResolution.APPLIED, REASON).resolution())
                .isEqualTo(AuditResolution.APPLIED);
    }

    @Test
    void 결과가_정해진_행은_해소할_수_없고_거절도_행으로_남는다() {
        audit.rows.put(TARGET, row(AuditResult.SUCCEEDED, NOW.minus(Duration.ofHours(1))));

        assertThatThrownBy(() -> service.resolve("kim", TARGET, AuditResolution.APPLIED, REASON))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(AuditErrorCode.NOT_RESOLVABLE);
                    assertThat(e.details()).containsEntry("currentResult", "SUCCEEDED")
                            .containsKey("auditId").containsEntry("targetAuditId", TARGET.toString());
                });
        assertThat(journal).containsExactly("begin", "lock", "findResolution", "record REJECTED", "commit");
        assertThat(counted("REJECTED")).isEqualTo(1.0);
        assertThat(counted("SUCCEEDED")).isZero();
    }

    @Test
    void 해소는_한_번이다_두_번째는_앞의_해소_행을_가리킨다() {
        audit.rows.put(TARGET, row(AuditResult.UNKNOWN, NOW.minus(Duration.ofHours(1))));
        UUID first = service.resolve("kim", TARGET, AuditResolution.NOT_APPLIED, REASON).auditId();

        assertThatThrownBy(() -> service.resolve("lee", TARGET, AuditResolution.APPLIED, "다르게 본다"))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(AuditErrorCode.ALREADY_RESOLVED);
                    assertThat(e.details()).containsEntry("resolutionId", first.toString());
                });
        assertThat(audit.recorded).extracting(AuditLog.Entry::actor).containsExactly("kim", "lee");
        assertThat(audit.results).containsExactly(AuditResult.SUCCEEDED, AuditResult.REJECTED);
    }

    @Test
    void 없는_행은_404_이고_거절_행이_남는다() {
        assertThatThrownBy(() -> service.resolve("kim", TARGET, AuditResolution.APPLIED, REASON))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode().status()).isEqualTo(404));
        assertThat(audit.results).containsExactly(AuditResult.REJECTED);
    }

    @Test
    void reason_이_틀리면_400_이고_아무것도_하지_않는다() {
        audit.rows.put(TARGET, row(AuditResult.UNKNOWN, NOW.minus(Duration.ofHours(1))));

        for (String bad : new String[] {"", "   ", "x".repeat(ResolveAuditUseCase.MAX_REASON_LENGTH + 1)}) {
            assertThatThrownBy(() -> service.resolve("kim", TARGET, AuditResolution.APPLIED, bad))
                    .isInstanceOf(ValidationException.class);
        }
        assertThat(journal).as("커맨드가 성립하지 않았다 — 트랜잭션도 행도 없다").isEmpty();
        assertThat(counted("REJECTED")).isZero();
    }

    @Test
    void 커밋에_실패하면_세지_않는다() {
        // CLAUDE.md 「카운터는 커밋 뒤에 센다 — 그리고 테스트가 그 순서를 본다」.
        audit.rows.put(TARGET, row(AuditResult.UNKNOWN, NOW.minus(Duration.ofHours(1))));
        transactions.failCommit = true;

        assertThatThrownBy(() -> service.resolve("kim", TARGET, AuditResolution.APPLIED, REASON))
                .hasMessageContaining("커밋 실패");
        assertThat(journal).as("전제 — 행은 썼고 커밋에서 실패했다").contains("record SUCCEEDED");
        assertThat(counted("SUCCEEDED")).isZero();
    }

    private static AuditLog.Row row(AuditResult result, Instant createdAt) {
        return new AuditLog.Row(TARGET, "CLOSE_WAVE", result, createdAt);
    }

    private double counted(String result) {
        Counter counter = registry.find(DawnlineMetrics.OPS_COMMANDS.meterName())
                .tag("action", "RESOLVE_AUDIT").tag("result", result).counter();
        return counter == null ? -1 : counter.count();
    }

    /** {@code audit_logs} 흉내 — 대상 행과 해소 행. */
    private final class FakeAudit implements AuditLog {
        final Map<UUID, Row> rows = new HashMap<>();
        final List<Entry> recorded = new ArrayList<>();
        final List<AuditResult> results = new ArrayList<>();

        @Override
        public void open(Entry entry) {
            throw new UnsupportedOperationException("해소에는 PENDING 단계가 없다");
        }

        @Override
        public void close(UUID id, AuditResult result) {
            throw new UnsupportedOperationException("해소는 대상 행을 고치지 않는다");
        }

        @Override
        public void record(Entry entry, AuditResult result) {
            journal.add("record " + result);
            recorded.add(entry);
            results.add(result);
        }

        @Override
        public Optional<Row> lockForResolution(UUID id) {
            journal.add("lock");
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<UUID> findResolution(UUID target) {
            journal.add("findResolution");
            for (int i = 0; i < recorded.size(); i++) {
                if (target.equals(recorded.get(i).targetId()) && results.get(i) == AuditResult.SUCCEEDED) {
                    return Optional.of(recorded.get(i).id());
                }
            }
            return Optional.empty();
        }
    }

    /** 트랜잭션을 흉내 내지 않고 일지에 적는다. */
    private final class FakeTransactions implements PlatformTransactionManager {
        boolean failCommit;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            journal.add("begin");
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            if (failCommit) {
                throw new IllegalStateException("커밋 실패");
            }
            journal.add("commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            journal.add("rollback");
        }
    }
}
