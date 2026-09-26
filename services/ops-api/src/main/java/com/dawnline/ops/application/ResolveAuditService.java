package com.dawnline.ops.application;

import com.dawnline.common.Ids;
import com.dawnline.common.error.CommonErrorCode;
import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.ValidationException;
import com.dawnline.observability.MdcKeys;
import com.dawnline.observability.MdcScope;
import com.dawnline.ops.application.port.in.ResolveAuditUseCase;
import com.dawnline.ops.application.port.out.AuditLog;
import com.dawnline.ops.domain.AuditErrorCode;
import com.dawnline.ops.domain.AuditResolution;
import com.dawnline.ops.domain.AuditResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 감사 해소는 칸이 아니라 행이다 (DESIGN.md §5.5 「감사 해소」, ADR-065).
 *
 * <h2>한 트랜잭션 — 잠금 · 판정 · 삽입</h2>
 * 위임이 없으므로 {@code PENDING} 단계가 없다. 대상 행을 {@code FOR UPDATE} 로 잡고, 해소할 수 있는지 보고, 해소 행을 쓴다 —
 * 같은 행을 두 사람이 동시에 해소하면 둘째는 첫째의 커밋을 기다린 뒤 그 해소 행을 보고 {@code audit-already-resolved} 로 끝난다.
 * 대상 행은 <strong>고치지 않는다</strong> — 「그때 모른다고 적었다」는 사실이 표에 남는다.
 *
 * <h2>거절도 행이다</h2>
 * 404 · 409 는 {@code result=REJECTED} 인 해소 행을 남긴다 — 코어의 4xx 가 {@code REJECTED} 로 남는 것과 같다(모든 커맨드는
 * {@code audit_logs} 에). {@code reason} 이 틀린 400 은 커맨드가 성립하지 않은 것이라 남기지 않는다(조기 마감과 같은 규칙).
 *
 * <p>카운터는 커밋 뒤에 센다(CLAUDE.md). 쓰지 못하면 세지 않는다.
 */
public class ResolveAuditService implements ResolveAuditUseCase {

    /** 이보다 오래된 {@code PENDING} 은 해소할 수 있다 — RB-07 의 정의(위임 타임아웃은 최대 60초다, §5.5). */
    static final Duration STALE_PENDING = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(ResolveAuditService.class);

    private final AuditLog audit;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MeterRegistry registry;

    /**
     * @param audit              {@code audit_logs}
     * @param transactionManager 잠금 · 판정 · 삽입을 한 트랜잭션으로
     * @param clock              주입된 시계 (불변규칙 12)
     * @param registry           카운터 레지스트리 — {@code action="RESOLVE_AUDIT"} 를 기동 때 미리 등록한다
     */
    public ResolveAuditService(AuditLog audit, PlatformTransactionManager transactionManager, Clock clock,
            MeterRegistry registry) {
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registry = Objects.requireNonNull(registry, "registry");
        OpsCommandService.registerCommandCounters(registry, ACTION);
    }

    @Override
    public Resolved resolve(String actor, UUID target, AuditResolution resolution, String reason) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(resolution, "resolution");
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw new ValidationException("reason 은 1~" + MAX_REASON_LENGTH + "자여야 합니다 — 무엇을 근거로 닫았는지 적는다",
                    Map.of("field", "reason"));
        }
        UUID auditId = Ids.newId();
        return MdcScope.builder().put(MdcKeys.AUDIT_ID, auditId).call(() -> {
            Instant now = clock.instant();
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("resolution", resolution.name());
            request.put("reason", reason);
            AuditLog.Entry entry = new AuditLog.Entry(auditId, actor, ACTION, TARGET_TYPE, target, request, now);

            Optional<Rejection> rejection = Objects.requireNonNull(transactions.execute(status -> {
                Optional<Rejection> judged = judge(target, audit.lockForResolution(target), now);
                audit.record(entry, judged.isEmpty() ? AuditResult.SUCCEEDED : AuditResult.REJECTED);
                return judged;
            }));
            // 커밋 뒤다 — transactions.execute 가 돌아왔다.
            if (rejection.isPresent()) {
                OpsCommandService.commandCounter(registry, ACTION, AuditResult.REJECTED).increment();
                throw rejection.get().toException(auditId, target);
            }
            OpsCommandService.commandCounter(registry, ACTION, AuditResult.SUCCEEDED).increment();
            // reason 은 싣지 않는다 — 사람이 쓴 자유 문장이라 무엇이 들어올지 모른다(§9.3 개인정보). 감사 행에 있다.
            log.info("감사 행을 해소했습니다. target={} resolution={}", target, resolution);
            return new Resolved(auditId, target, resolution, reason, actor, now);
        });
    }

    /** 비어 있으면 해소할 수 있다. 잠근 뒤에 부른다 — 이미 해소됐는지는 잠근 뒤의 질의가 본다. */
    private Optional<Rejection> judge(UUID target, Optional<AuditLog.Row> locked, Instant now) {
        if (locked.isEmpty()) {
            return Optional.of(new Rejection.NotFound());
        }
        Optional<UUID> resolution = audit.findResolution(target);
        if (resolution.isPresent()) {
            return Optional.of(new Rejection.AlreadyResolved(resolution.get()));
        }
        AuditLog.Row row = locked.get();
        boolean resolvable = row.result() == AuditResult.UNKNOWN
                || (row.result() == AuditResult.PENDING && row.createdAt().isBefore(now.minus(STALE_PENDING)));
        return resolvable ? Optional.empty() : Optional.of(new Rejection.NotResolvable(row.result()));
    }

    /** 거절 셋 — 트랜잭션 밖으로 들고 나가 커밋 뒤에 예외가 된다. */
    private sealed interface Rejection {

        record NotFound() implements Rejection {
        }

        record AlreadyResolved(UUID resolutionId) implements Rejection {
        }

        record NotResolvable(AuditResult current) implements Rejection {
        }

        default DomainException toException(UUID auditId, UUID target) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("auditId", auditId.toString());
            details.put("targetAuditId", target.toString());
            return switch (this) {
                case NotFound _ -> new DomainException(CommonErrorCode.NOT_FOUND,
                        "그런 감사 행이 없습니다", details);
                case AlreadyResolved already -> {
                    details.put("resolutionId", already.resolutionId().toString());
                    yield new DomainException(AuditErrorCode.ALREADY_RESOLVED,
                            "이미 해소된 감사 행입니다 — resolutionId 의 행을 본다", details);
                }
                case NotResolvable not -> {
                    details.put("currentResult", not.current().name());
                    yield new DomainException(AuditErrorCode.NOT_RESOLVABLE,
                            "해소할 수 있는 것은 UNKNOWN 과 5분 넘은 PENDING 뿐입니다", details);
                }
            };
        }
    }
}
