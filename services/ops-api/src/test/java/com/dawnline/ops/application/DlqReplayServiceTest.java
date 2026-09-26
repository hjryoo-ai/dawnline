package com.dawnline.ops.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.DomainException;
import com.dawnline.observability.DawnlineMetrics;
import com.dawnline.observability.MdcKeys;
import com.dawnline.ops.application.port.in.ReplayDeadLettersUseCase.RecordRef;
import com.dawnline.ops.application.port.in.ReplayDeadLettersUseCase.Replayed;
import com.dawnline.ops.application.port.out.AuditLog;
import com.dawnline.ops.application.port.out.DeadLetters;
import com.dawnline.ops.domain.AuditResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * 재처리의 순서 — 읽기 → 기록 → 보내기 → 닫기 → 세기 (DESIGN.md §4.6 「DLQ 재처리」, ADR-053 결정 3·4).
 *
 * <p>가짜 둘이 한 일지에 적고, 테스트는 일지를 본다({@code OpsCommandServiceTest} 와 같은 형태).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("DlqReplayService — 원래 바이트를 원래 그룹에게만, 기록한 뒤에")
class DlqReplayServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T05:00:00Z");
    private static final String TOPIC = "dawnline.order.placed.v1";
    private static final UUID EVENT = UUID.fromString("0199a000-0000-7000-8000-0000000000e1");
    private static final RecordRef REF = new RecordRef(3, 17);

    private final List<String> journal = new ArrayList<>();
    private final FakeAudit audit = new FakeAudit();
    private final FakeLetters letters = new FakeLetters();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DlqReplayService service = new DlqReplayService(letters, audit, Set.of(TOPIC),
            Clock.fixed(NOW, ZoneOffset.UTC), registry);

    @Test
    void 만들면_재처리의_결과_넷이_0_으로_이미_있다() {
        // §9.1 「없는 시계열은 0 으로 보인다」 — 서비스 필드가 만들어진 것만으로 등록돼 있어야 한다.
        for (String result : List.of("SUCCEEDED", "REJECTED", "FAILED", "UNKNOWN")) {
            assertThat(counted(result)).as(result).isZero();
        }
    }

    @Test
    void 읽고_기록을_커밋한_뒤에_보내고_결과로_닫은_뒤에_센다() {
        letters.put(REF, letter("fulfillment-service"));

        List<Replayed> replayed = service.replay("kim", TOPIC, List.of(REF));

        assertThat(journal).containsExactly("read 3@17", "open PENDING", "republish →fulfillment-service",
                "close SUCCEEDED");
        assertThat(counted("SUCCEEDED")).isEqualTo(1.0);
        assertThat(replayed).singleElement().satisfies(r -> {
            assertThat(r.result()).isEqualTo(AuditResult.SUCCEEDED);
            assertThat(r.eventId()).isEqualTo(EVENT);
            assertThat(r.auditId()).isEqualTo(audit.entries.getFirst().id());
            assertThat(r.detail()).isNull();
        });
    }

    @Test
    void 감사_행은_이벤트를_가리키고_누가_어디서_누구에게를_싣는다() {
        letters.put(REF, letter("fulfillment-service"));

        service.replay("kim", TOPIC, List.of(REF));

        AuditLog.Entry entry = audit.entries.getFirst();
        assertThat(entry.actor()).isEqualTo("kim");
        assertThat(entry.action()).isEqualTo("DLQ_REPLAY");
        assertThat(entry.targetType()).isEqualTo("EVENT");
        assertThat(entry.targetId()).as("「이 이벤트를 누가 재처리했나」가 eventId 로 나온다").isEqualTo(EVENT);
        assertThat(entry.request()).containsExactlyInAnyOrderEntriesOf(Map.of("topic", TOPIC, "partition", 3,
                "offset", 17L, "targetGroup", "fulfillment-service"));
        assertThat(entry.createdAt()).isEqualTo(NOW);
    }

    @Test
    void 받은_바이트를_그대로_포트에_돌려준다() {
        DeadLetters.DeadLetter letter = letter("fulfillment-service");
        letters.put(REF, letter);

        service.replay("kim", TOPIC, List.of(REF));

        assertThat(letters.republished).as("열어서 다시 만든 것이 아니라 읽은 그 레코드다").containsExactly(letter);
    }

    @Test
    void 원래_그룹을_모르면_보내지_않는다() {
        letters.put(REF, letter(null));

        Replayed replayed = service.replay("kim", TOPIC, List.of(REF)).getFirst();

        assertThat(replayed.result()).isEqualTo(AuditResult.REJECTED);
        assertThat(replayed.detail()).isEqualTo(DlqReplayService.NO_ORIGINAL_GROUP);
        assertThat(journal).as("대상을 모르는 재처리는 모든 그룹에게 간다 — ADR-053 이 막는 모양")
                .doesNotContain("republish →null").containsExactly("read 3@17", "open PENDING", "close REJECTED");
        assertThat(audit.entries.getFirst().targetId()).as("그래도 무엇을 거절했는지는 남는다").isEqualTo(EVENT);
    }

    @Test
    void 레코드가_없으면_보내지_않고_대상도_비어_있다() {
        Replayed replayed = service.replay("kim", TOPIC, List.of(REF)).getFirst();

        assertThat(replayed.result()).isEqualTo(AuditResult.REJECTED);
        assertThat(replayed.detail()).isEqualTo(DlqReplayService.NOT_FOUND);
        assertThat(audit.entries.getFirst().targetId()).isNull();
        assertThat(audit.entries.getFirst().request()).doesNotContainKey("targetGroup");
    }

    @Test
    void 다른_토픽에서_온_레코드는_보내지_않는다() {
        letters.put(REF, new DeadLetters.DeadLetter(3, 17, NOW, "dawnline.order.cancelled.v1", 3, 5L,
                "fulfillment-service", "order.cancelled", EVENT, null, raw()));

        assertThat(service.replay("kim", TOPIC, List.of(REF)).getFirst().detail())
                .isEqualTo(DlqReplayService.ORIGINAL_TOPIC_MISMATCH);
        assertThat(letters.republished).isEmpty();
    }

    @Test
    void 읽지_못하면_FAILED_이고_보내지_않았다() {
        letters.readFailure = () -> new IllegalStateException("브로커가 없다");

        Replayed replayed = service.replay("kim", TOPIC, List.of(REF)).getFirst();

        assertThat(replayed.result()).as("보내지 않은 것이 확실하다").isEqualTo(AuditResult.FAILED);
        assertThat(journal).containsExactly("open PENDING", "close FAILED");
    }

    @Test
    void 브로커의_답이_감사_결과를_정한다() {
        Map<DeadLetters.Delivery, AuditResult> expected = Map.of(
                new DeadLetters.Delivery.Acked(), AuditResult.SUCCEEDED,
                new DeadLetters.Delivery.Refused("RecordTooLargeException"), AuditResult.FAILED,
                new DeadLetters.Delivery.Unknown("TimeoutException"), AuditResult.UNKNOWN);
        letters.put(REF, letter("fulfillment-service"));

        expected.forEach((delivery, result) -> {
            letters.delivery = () -> delivery;
            assertThat(service.replay("kim", TOPIC, List.of(REF)).getFirst().result()).as("%s", delivery)
                    .isEqualTo(result);
        });
    }

    @Test
    void 어댑터가_예외를_새게_하면_보냈는지_모르므로_UNKNOWN_이다() {
        letters.put(REF, letter("fulfillment-service"));
        letters.delivery = () -> {
            throw new IllegalStateException("어댑터 버그");
        };

        assertThat(service.replay("kim", TOPIC, List.of(REF)).getFirst().result()).isEqualTo(AuditResult.UNKNOWN);
        assertThat(counted("UNKNOWN")).isEqualTo(1.0);
    }

    @Test
    void 기록을_쓰지_못하면_보내지_않는다() {
        letters.put(REF, letter("fulfillment-service"));
        audit.failOpen = true;

        assertThatThrownBy(() -> service.replay("kim", TOPIC, List.of(REF)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("unavailable");
        assertThat(letters.republished).as("기록 없는 재처리는 없다").isEmpty();
        // 시계열은 미리 등록돼 있다(§9.1) — 「세지 않았다」는 전부 0 이라는 뜻이다.
        assertThat(registry.get(DawnlineMetrics.OPS_COMMANDS.meterName()).counters()).isNotEmpty()
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    void 결과를_닫지_못하면_세지_않는다() {
        letters.put(REF, letter("fulfillment-service"));
        audit.failClose = true;

        Replayed replayed = service.replay("kim", TOPIC, List.of(REF)).getFirst();

        assertThat(registry.get(DawnlineMetrics.OPS_COMMANDS.meterName()).counters()).as("행은 PENDING 으로 남았다 — 카운터가 다른 말을 하면 안 된다")
                .isNotEmpty().allSatisfy(counter -> assertThat(counter.count()).isZero());
        assertThat(replayed.result()).as("브로커는 받았다 — 운영자에게는 그대로 알린다").isEqualTo(AuditResult.SUCCEEDED);
    }

    @Test
    void 레코드마다_감사_행이_하나이고_준_순서대로_답한다() {
        RecordRef other = new RecordRef(0, 4);
        letters.put(REF, letter("fulfillment-service"));

        List<Replayed> replayed = service.replay("kim", TOPIC, List.of(REF, other));

        assertThat(replayed).extracting(Replayed::record).containsExactly(REF, other);
        assertThat(replayed).extracting(Replayed::result).containsExactly(AuditResult.SUCCEEDED, AuditResult.REJECTED);
        assertThat(audit.entries).hasSize(2);
        assertThat(audit.entries.get(0).id()).isNotEqualTo(audit.entries.get(1).id());
    }

    @Test
    void 계약에_없는_토픽은_404_이고_아무것도_기록하지_않는다() {
        assertThatThrownBy(() -> service.replay("kim", "dawnline.order.placed.v1.dlq", List.of(REF)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("not-found");
        assertThatThrownBy(() -> service.list("nope", 10)).isInstanceOf(DomainException.class);
        assertThat(journal).as("시도한 것이 없다").isEmpty();
    }

    @Test
    void 한_번에_1개_이상_100개_이하다() {
        List<RecordRef> tooMany = IntStream.range(0, 101).mapToObj(i -> new RecordRef(0, i)).toList();

        assertThatThrownBy(() -> service.replay("kim", TOPIC, List.of())).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("validation-failed");
        assertThatThrownBy(() -> service.replay("kim", TOPIC, tooMany)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> service.list(TOPIC, 0)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> service.list(TOPIC, 501)).isInstanceOf(DomainException.class);
        assertThat(journal).isEmpty();
    }

    @Test
    void 보내는_동안_로그에_감사_id_와_eventId_가_있고_끝나면_지워진다() {
        letters.put(REF, letter("fulfillment-service"));
        List<@Nullable String> during = new ArrayList<>();
        letters.delivery = () -> {
            during.add(MDC.get(MdcKeys.AUDIT_ID));
            during.add(MDC.get(MdcKeys.EVENT_ID));
            return new DeadLetters.Delivery.Acked();
        };

        Replayed replayed = service.replay("kim", TOPIC, List.of(REF)).getFirst();

        assertThat(during).containsExactly(replayed.auditId().toString(), EVENT.toString());
        assertThat(MDC.get(MdcKeys.AUDIT_ID)).isNull();
        assertThat(MDC.get(MdcKeys.EVENT_ID)).isNull();
    }

    private double counted(String result) {
        return registry.get(DawnlineMetrics.OPS_COMMANDS.meterName()).tag("action", "DLQ_REPLAY").tag("result", result).counter()
                .count();
    }

    private static DeadLetters.DeadLetter letter(@Nullable String originalGroup) {
        return new DeadLetters.DeadLetter(3, 17, NOW, TOPIC, 3, 5L, originalGroup, "order.placed", EVENT,
                "com.dawnline.messaging.kafka.NonRetryableEventException", raw());
    }

    private static DeadLetters.Raw raw() {
        return new DeadLetters.Raw(new byte[] {1}, new byte[] {2}, List.of());
    }

    private final class FakeLetters implements DeadLetters {
        final Map<RecordRef, DeadLetter> records = new HashMap<>();
        final List<DeadLetter> republished = new ArrayList<>();
        @Nullable Supplier<RuntimeException> readFailure;
        Supplier<Delivery> delivery = Delivery.Acked::new;

        void put(RecordRef ref, DeadLetter letter) {
            records.put(ref, letter);
        }

        @Override
        public List<DeadLetter> peek(String topic, int limit) {
            return List.copyOf(records.values());
        }

        @Override
        public Optional<DeadLetter> read(String topic, int partition, long offset) {
            if (readFailure != null) {
                throw readFailure.get();
            }
            journal.add("read " + partition + "@" + offset);
            return Optional.ofNullable(records.get(new RecordRef(partition, offset)));
        }

        @Override
        public Delivery republish(DeadLetter letter, String targetGroup) {
            journal.add("republish →" + targetGroup);
            republished.add(letter);
            return delivery.get();
        }
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
