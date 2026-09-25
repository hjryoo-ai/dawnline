package com.dawnline.ops.application;

import com.dawnline.common.Ids;
import com.dawnline.common.error.CommonErrorCode;
import com.dawnline.common.error.DomainException;
import com.dawnline.observability.DawnlineMeters;
import com.dawnline.observability.DawnlineMetrics;
import com.dawnline.observability.MdcKeys;
import com.dawnline.observability.MdcScope;
import com.dawnline.ops.application.port.in.OpsCommand;
import com.dawnline.ops.application.port.in.RunOpsCommandUseCase;
import com.dawnline.ops.application.port.out.AuditLog;
import com.dawnline.ops.application.port.out.CoreCommands;
import com.dawnline.ops.application.port.out.CoreReply;
import com.dawnline.ops.domain.AuditResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 감사 행을 먼저 쓰고, 위임하고, 결과로 닫는다 (DESIGN.md §5.5 「커맨드 위임」, ADR-052 결정 3).
 *
 * <h2>순서가 곧 규칙이다</h2>
 * <ol>
 *   <li>{@code PENDING} 을 <strong>커밋한다</strong>. 쓰지 못하면 위임하지 않는다 — 기록 없는 커맨드는 없다.</li>
 *   <li>위임한다. 이 사이에 죽으면 행은 {@code PENDING} 으로 남고, 그것이 「무언가 하다 멈췄다」는
 *       유일한 흔적이다(RB-07). 순서가 반대면 그 흔적이 없다.</li>
 *   <li>결과로 닫고, <strong>닫은 뒤에</strong> 센다. 닫지 못하면 세지 않는다 — 행은 {@code PENDING} 으로
 *       남고 카운터가 그와 다른 말을 하면 안 된다(CLAUDE.md 「카운터는 커밋 뒤에 센다」).</li>
 * </ol>
 *
 * <p>이 클래스에는 트랜잭션이 없다. 두 쓰기는 각자 자기 트랜잭션이고, 위임은 그 둘 사이에 있다.
 */
public class OpsCommandService implements RunOpsCommandUseCase {

    private static final Logger log = LoggerFactory.getLogger(OpsCommandService.class);

    private final AuditLog audit;
    private final CoreCommands core;
    private final Clock clock;
    private final MeterRegistry registry;

    /**
     * @param audit    {@code audit_logs}
     * @param core     코어 위임
     * @param clock    주입된 시계 (불변규칙 12)
     * @param registry 카운터 레지스트리
     */
    public OpsCommandService(AuditLog audit, CoreCommands core, Clock clock, MeterRegistry registry) {
        this.audit = Objects.requireNonNull(audit, "audit");
        this.core = Objects.requireNonNull(core, "core");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registry = Objects.requireNonNull(registry, "registry");
        OpsCommand.ACTIONS.forEach(action -> registerCommandCounters(registry, action));
    }

    /**
     * {@code action} 의 결과 넷을 0 으로 미리 등록한다 (DESIGN.md §9.1 「없는 시계열은 0 으로 보인다」).
     * 처음 셀 때 만들면 첫 {@code UNKNOWN} 에서 시계열이 1 로 태어나고, {@code increase()} 는 그 첫 증가를 읽지
     * 못한다 — §9.4 의 알림이 가장 중요한 첫 번째를 놓친다. {@code PENDING} 은 세지 않는 값이라 뺀다.
     *
     * @param registry 카운터 레지스트리
     * @param action   {@code audit_logs.action}
     */
    static void registerCommandCounters(MeterRegistry registry, String action) {
        for (AuditResult result : AuditResult.values()) {
            if (result != AuditResult.PENDING) {
                commandCounter(registry, action, result);
            }
        }
    }

    /** 같은 이름·태그·설명으로 등록한다 — 미리 등록한 것과 세는 것이 한 시계열이어야 한다. */
    static Counter commandCounter(MeterRegistry registry, String action, AuditResult result) {
        return DawnlineMeters.counter(registry, DawnlineMetrics.OPS_COMMANDS, "action", action, "result", result.name());
    }

    @Override
    public Outcome run(String actor, OpsCommand command) {
        UUID auditId = Ids.newId();
        return MdcScope.builder().put(MdcKeys.AUDIT_ID, auditId).call(() -> {
            open(auditId, actor, command);
            CoreReply reply = delegate(auditId, command);
            close(auditId, command, reply);
            return new Outcome(auditId, reply);
        });
    }

    private void open(UUID auditId, String actor, OpsCommand command) {
        try {
            audit.open(new AuditLog.Entry(auditId, actor, command.action(), command.targetType(),
                    command.targetId(), command.arguments(), clock.instant()));
        } catch (RuntimeException e) {
            throw new DomainException(CommonErrorCode.UNAVAILABLE,
                    "감사 기록을 쓸 수 없어 위임하지 않았다 — 기록 없는 커맨드는 없다", Map.of("action", command.action()), e);
        }
    }

    /** 포트는 예외를 던지지 않는 것이 계약이다. 그래도 새어 나오면 코어에 닿았는지 모르므로 {@code UNKNOWN}. */
    private CoreReply delegate(UUID auditId, OpsCommand command) {
        try {
            return core.delegate(auditId, command);
        } catch (RuntimeException e) {
            log.error("위임 어댑터가 예외를 냈다 — 코어에 닿았는지 모른다 action={}", command.action(), e);
            return new CoreReply.Unknown(false, null, e.toString());
        }
    }

    private void close(UUID auditId, OpsCommand command, CoreReply reply) {
        try {
            audit.close(auditId, reply.result());
        } catch (RuntimeException e) {
            log.error("감사 결과를 쓰지 못했다 — 행은 PENDING 으로 남는다(RB-07) action={} result={}",
                    command.action(), reply.result(), e);
            return;
        }
        commandCounter(registry, command.action(), reply.result()).increment();
        if (reply instanceof CoreReply.Unknown unknown) {
            log.warn("코어에 적용됐는지 모른다 — 사람이 auditId 로 코어 로그를 보고 닫는다(RB-07) action={} detail={}",
                    command.action(), unknown.detail());
        }
    }
}
