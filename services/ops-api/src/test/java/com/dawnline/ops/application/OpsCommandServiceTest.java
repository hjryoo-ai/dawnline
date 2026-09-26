package com.dawnline.ops.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.DomainException;
import com.dawnline.observability.DawnlineMetrics;
import com.dawnline.observability.MdcKeys;
import com.dawnline.ops.application.port.in.OpsCommand;
import com.dawnline.ops.application.port.in.RunOpsCommandUseCase.Outcome;
import com.dawnline.ops.application.port.out.AuditLog;
import com.dawnline.ops.application.port.out.CoreCommands;
import com.dawnline.ops.application.port.out.CoreReply;
import com.dawnline.ops.domain.AuditResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * 감사 행의 순서 — 기록 → 위임 → 닫기 → 세기 (DESIGN.md §5.5 「커맨드 위임」, ADR-052 결정 3).
 *
 * <p>순서는 코드를 읽어서는 보이지 않으므로 테스트가 그 순서를 어설션한다(CLAUDE.md 「카운터는 커밋 뒤에
 * 센다」). 가짜 둘이 한 일지에 적고, 테스트는 일지를 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("OpsCommandService — 기록 → 위임 → 닫기 → 세기")
class OpsCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T05:00:00Z");
    private static final UUID WAVE = UUID.fromString("0199a000-0000-7000-8000-0000000000a1");
    private static final OpsCommand RUN = new OpsCommand.RunPlan(WAVE, null, null, "FAST");

    private final List<String> journal = new ArrayList<>();
    private final FakeAudit audit = new FakeAudit();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void 만들면_모든_커맨드의_결과_넷이_0_으로_이미_있다() {
        // §9.1 「없는 시계열은 0 으로 보인다」 — 첫 UNKNOWN 이 1 로 태어나면 increase() 가 그것을 못 읽는다.
        service(id -> applied());

        for (String action : OpsCommand.ACTIONS) {
            for (String result : List.of("SUCCEEDED", "REJECTED", "FAILED", "UNKNOWN")) {
                assertThat(counted(action, result)).as("%s %s", action, result).isZero();
            }
            assertThat(registry.find(DawnlineMetrics.OPS_COMMANDS.meterName()).tag("action", action).tag("result", "PENDING")
                    .counter()).as("PENDING 은 세지 않는 값이다").isNull();
        }
    }

    @Test
    void 기록을_커밋한_뒤에_위임하고_결과로_닫은_뒤에_센다() {
        Outcome outcome = service(id -> applied()).run("kim", RUN);

        assertThat(journal).containsExactly("open PENDING", "delegate", "close SUCCEEDED");
        assertThat(counted("RUN_PLAN", "SUCCEEDED")).isEqualTo(1.0);
        assertThat(outcome.reply()).isInstanceOf(CoreReply.Applied.class);
    }

    @Test
    void 감사_행과_코어가_받은_id_가_같고_행에는_인자만_실린다() {
        List<UUID> seenByCore = new ArrayList<>();
        Outcome outcome = service(id -> {
            seenByCore.add(id);
            return applied();
        }).run("kim", RUN);

        AuditLog.Entry entry = audit.entries.getFirst();
        assertThat(seenByCore).containsExactly(entry.id());
        assertThat(outcome.auditId()).isEqualTo(entry.id());
        assertThat(entry.actor()).isEqualTo("kim");
        assertThat(entry.action()).isEqualTo("RUN_PLAN");
        assertThat(entry.targetType()).isEqualTo("WAVE");
        assertThat(entry.targetId()).isEqualTo(WAVE);
        assertThat(entry.request()).as("주지 않은 campId·strategy 는 null 로 적지 않는다")
                .containsOnlyKeys("waveId", "mode");
        assertThat(entry.createdAt()).isEqualTo(NOW);
    }

    @Test
    void 기록을_쓰지_못하면_위임하지_않는다() {
        audit.failOpen = true;

        assertThatThrownBy(() -> service(id -> applied()).run("kim", RUN))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("unavailable");
        assertThat(journal).as("기록 없는 커맨드는 없다").doesNotContain("delegate");
        // 시계열은 미리 등록돼 있다(§9.1) — 「세지 않았다」는 전부 0 이라는 뜻이다.
        assertThat(registry.get(DawnlineMetrics.OPS_COMMANDS.meterName()).counters()).isNotEmpty()
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    void 결과를_닫지_못하면_세지_않는다() {
        audit.failClose = true;

        Outcome outcome = service(id -> applied()).run("kim", RUN);

        assertThat(journal).containsExactly("open PENDING", "delegate");
        assertThat(registry.get(DawnlineMetrics.OPS_COMMANDS.meterName()).counters()).as("행은 PENDING 으로 남았다 — 카운터가 다른 말을 하면 안 된다")
                .isNotEmpty().allSatisfy(counter -> assertThat(counter.count()).isZero());
        assertThat(outcome.reply()).as("코어는 적용했다 — 운영자에게는 그대로 알린다").isInstanceOf(CoreReply.Applied.class);
    }

    @Test
    void 어댑터가_예외를_새게_하면_닿았는지_모르므로_UNKNOWN_이다() {
        Outcome outcome = service(id -> {
            throw new IllegalStateException("어댑터 버그");
        }).run("kim", RUN);

        assertThat(outcome.reply()).isInstanceOf(CoreReply.Unknown.class);
        assertThat(journal).containsExactly("open PENDING", "delegate", "close UNKNOWN");
        assertThat(counted("RUN_PLAN", "UNKNOWN")).isEqualTo(1.0);
    }

    @Test
    void 답의_갈래가_감사_결과를_정한다() {
        Map<CoreReply, AuditResult> expected = new LinkedHashMap<>();
        expected.put(applied(), AuditResult.SUCCEEDED);
        expected.put(new CoreReply.Rejected(409, "{}", null), AuditResult.REJECTED);
        expected.put(new CoreReply.Unreachable("refused"), AuditResult.FAILED);
        expected.put(new CoreReply.Unknown(true, null, "timeout"), AuditResult.UNKNOWN);
        expected.put(new CoreReply.Unknown(false, 500, "boom"), AuditResult.UNKNOWN);

        expected.forEach((reply, result) -> {
            journal.clear();
            service(id -> reply).run("kim", RUN);
            assertThat(journal.getLast()).as("%s", reply).isEqualTo("close " + result);
        });
    }

    @Test
    void 위임_동안_로그에_감사_id_가_있고_끝나면_지워진다() {
        List<@Nullable String> duringDelegate = new ArrayList<>();
        Outcome outcome = service(id -> {
            duringDelegate.add(MDC.get(MdcKeys.AUDIT_ID));
            return applied();
        }).run("kim", RUN);

        assertThat(duringDelegate).containsExactly(outcome.auditId().toString());
        assertThat(MDC.get(MdcKeys.AUDIT_ID)).isNull();
    }

    private OpsCommandService service(Function<UUID, CoreReply> core) {
        CoreCommands delegating = (auditId, command) -> {
            journal.add("delegate");
            return core.apply(auditId);
        };
        return new OpsCommandService(audit, delegating, Clock.fixed(NOW, ZoneOffset.UTC), registry);
    }

    private double counted(String action, String result) {
        return registry.get(DawnlineMetrics.OPS_COMMANDS.meterName()).tag("action", action).tag("result", result).counter().count();
    }

    private static CoreReply applied() {
        return new CoreReply.Applied(new CoreReply.PlanRun(WAVE, "PLANNED"));
    }

    private final class FakeAudit implements AuditLog {
        final List<Entry> entries = new ArrayList<>();
        boolean failOpen;
        boolean failClose;

        @Override
        public void open(Entry entry) {
            if (failOpen) {
                throw new IllegalStateException("DB 가 없다");
            }
            entries.add(entry);
            journal.add("open PENDING");
        }

        @Override
        public void close(UUID id, AuditResult result) {
            if (failClose) {
                throw new IllegalStateException("DB 가 없다");
            }
            journal.add("close " + result);
        }

        @Override
        public void record(Entry entry, AuditResult result) {
            throw new UnsupportedOperationException("이 서비스는 해소 행을 쓰지 않는다");
        }

        @Override
        public java.util.Optional<Row> lockForResolution(UUID id) {
            throw new UnsupportedOperationException("이 서비스는 해소하지 않는다");
        }

        @Override
        public java.util.Optional<UUID> findResolution(UUID target) {
            throw new UnsupportedOperationException("이 서비스는 해소하지 않는다");
        }
    }
}
