package com.dawnline.ops.application;

import com.dawnline.common.Ids;
import com.dawnline.common.error.CommonErrorCode;
import com.dawnline.common.error.DomainException;
import com.dawnline.observability.MdcKeys;
import com.dawnline.observability.MdcScope;
import com.dawnline.ops.application.port.in.ReplayDeadLettersUseCase;
import com.dawnline.ops.application.port.out.AuditLog;
import com.dawnline.ops.application.port.out.DeadLetters;
import com.dawnline.ops.application.port.out.DeadLetters.DeadLetter;
import com.dawnline.ops.application.port.out.DeadLetters.Delivery;
import com.dawnline.ops.domain.AuditResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DLQ 재처리 — 원래 바이트를 원래 그룹에게만 (DESIGN.md §4.6 「DLQ 재처리」, ADR-053).
 *
 * <h2>두 축</h2>
 * <ul>
 *   <li><strong>{@code eventId} 가 유지되는가</strong> — 이 클래스는 value 를 열지 않는다. 포트가 읽은 원래 바이트를
 *       그대로 포트에 돌려준다. {@code eventId} 는 감사 행에 적으려고 어댑터가 읽어 둔 값일 뿐이다.</li>
 *   <li><strong>누가 눌렀는가</strong> — 레코드 하나에 감사 행 하나. {@code target_id} 가 {@code eventId} 다.</li>
 * </ul>
 *
 * <h2>순서가 곧 규칙이다</h2>
 * 레코드마다 — 읽는다(부작용 없음) → {@code PENDING} 을 <strong>커밋한다</strong> → 보낸다 → 결과로 닫는다 →
 * <strong>닫은 뒤에</strong> 센다. 커맨드 위임({@link OpsCommandService})과 같은 순서이고 같은 이유다. 이 발행에는
 * outbox 가 없다 — <strong>이 발행의 상태는 감사 행이다</strong>(ADR-053 결정 3). {@code PENDING} 이 「보낼 것이
 * 있다」를 먼저 적고 ack 가 그것을 닫는다.
 *
 * <h2>대상을 모르면 보내지 않는다</h2>
 * 원래 그룹 헤더가 없는 레코드는 {@code REJECTED} 다. 대상을 모르는 재처리는 모든 그룹에게 가고, 그것이 ADR-053 이
 * 막는 모양이다.
 *
 * <h2>다시 눌러도 된다</h2>
 * 대상 그룹이 첫 번째를 처리했으면 그 그룹의 {@code processed_events} 가 두 번째를 막는다. 그래서 {@code UNKNOWN}
 * 과 남은 {@code PENDING} 은 다시 누르는 것이 해소다(RB-05). 감사를 쓰지 못해 요청 중간에 503 으로 멈춰도 같다.
 */
public class DlqReplayService implements ReplayDeadLettersUseCase {

    private static final Logger log = LoggerFactory.getLogger(DlqReplayService.class);

    /** {@code audit_logs.action} — §5.5 · §9.1. */
    static final String ACTION = "DLQ_REPLAY";

    /** {@code audit_logs.target_type}. */
    static final String TARGET_TYPE = "EVENT";

    /** 거절 사유 — 보내기 전에 ops-api 가 멈춘 이유. */
    static final String NOT_FOUND = "not-found";
    static final String NO_ORIGINAL_GROUP = "no-original-group";
    static final String NO_ORIGINAL_PARTITION = "no-original-partition";
    static final String ORIGINAL_TOPIC_MISMATCH = "original-topic-mismatch";

    private final DeadLetters letters;
    private final AuditLog audit;
    private final Set<String> topics;
    private final Clock clock;
    private final MeterRegistry registry;

    /**
     * @param letters  DLQ
     * @param audit    {@code audit_logs}
     * @param topics   계약에 있는 토픽 — ops-api 가 구독하는 토픽 전부다(§5.5)
     * @param clock    주입된 시계 (불변규칙 12)
     * @param registry 카운터 레지스트리
     */
    public DlqReplayService(DeadLetters letters, AuditLog audit, Set<String> topics, Clock clock,
            MeterRegistry registry) {
        this.letters = Objects.requireNonNull(letters, "letters");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.topics = Set.copyOf(topics);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public List<DeadLetter> list(String topic, int limit) {
        requireContractTopic(topic);
        if (limit < 1 || limit > MAX_LISTED) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "limit 은 1 이상 " + MAX_LISTED + " 이하다",
                    Map.of("limit", limit));
        }
        return letters.peek(topic, limit);
    }

    @Override
    public List<Replayed> replay(String actor, String topic, List<RecordRef> records) {
        requireContractTopic(topic);
        if (records.isEmpty() || records.size() > MAX_RECORDS) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "한 번에 1 개 이상 " + MAX_RECORDS + " 개 이하를 고른다", Map.of("records", records.size()));
        }
        return records.stream().map(record -> replayOne(actor, topic, record)).toList();
    }

    private void requireContractTopic(String topic) {
        if (!topics.contains(topic)) {
            throw new DomainException(CommonErrorCode.NOT_FOUND, "계약에 없는 토픽이다", Map.of("topic", topic));
        }
    }

    private Replayed replayOne(String actor, String topic, RecordRef record) {
        UUID auditId = Ids.newId();
        return MdcScope.builder().put(MdcKeys.AUDIT_ID, auditId).call(() -> {
            Lookup lookup = lookup(topic, record);
            UUID eventId = lookup.eventId();
            return MdcScope.builder().eventId(eventId).call(() -> {
                open(auditId, actor, eventId, request(topic, record, lookup));
                Outcome outcome = switch (lookup) {
                    case Lookup.Found found -> send(found.letter(), Objects.requireNonNull(found.letter().originalGroup()));
                    case Lookup.Refused refused -> new Outcome(AuditResult.REJECTED, refused.reason());
                    case Lookup.Unreadable unreadable -> new Outcome(AuditResult.FAILED, unreadable.detail());
                };
                close(auditId, outcome);
                return new Replayed(record, auditId, eventId, outcome.result(), outcome.detail());
            });
        });
    }

    /** 읽고 가린다 — 부작용이 없다. 여기서 멈춘 것은 보내지 않은 것이 확실하다. */
    private Lookup lookup(String topic, RecordRef record) {
        Optional<DeadLetter> found;
        try {
            found = letters.read(topic, record.partition(), record.offset());
        } catch (RuntimeException e) {
            log.warn("DLQ 레코드를 읽지 못했다 — 보내지 않았다 topic={} partition={} offset={}",
                    topic, record.partition(), record.offset(), e);
            return new Lookup.Unreadable(e.getClass().getName());
        }
        if (found.isEmpty()) {
            return new Lookup.Refused(null, NOT_FOUND);
        }
        DeadLetter letter = found.get();
        if (!topic.equals(letter.originalTopic())) {
            return new Lookup.Refused(letter.eventId(), ORIGINAL_TOPIC_MISMATCH);
        }
        if (letter.originalGroup() == null) {
            return new Lookup.Refused(letter.eventId(), NO_ORIGINAL_GROUP);
        }
        if (letter.originalPartition() == null) {
            return new Lookup.Refused(letter.eventId(), NO_ORIGINAL_PARTITION);
        }
        return new Lookup.Found(letter);
    }

    private static Map<String, Object> request(String topic, RecordRef record, Lookup lookup) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("topic", topic);
        request.put("partition", record.partition());
        request.put("offset", record.offset());
        if (lookup instanceof Lookup.Found found) {
            request.put("targetGroup", Objects.requireNonNull(found.letter().originalGroup()));
        }
        return request;
    }

    private void open(UUID auditId, String actor, @Nullable UUID eventId, Map<String, Object> request) {
        try {
            audit.open(new AuditLog.Entry(auditId, actor, ACTION, TARGET_TYPE, eventId, request, clock.instant()));
        } catch (RuntimeException e) {
            throw new DomainException(CommonErrorCode.UNAVAILABLE,
                    "감사 기록을 쓸 수 없어 재처리하지 않았다 — 기록 없는 재처리는 없다", Map.of("action", ACTION), e);
        }
    }

    /** 포트는 예외를 던지지 않는 것이 계약이다. 그래도 새어 나오면 보냈는지 모르므로 {@code UNKNOWN}. */
    private Outcome send(DeadLetter letter, String targetGroup) {
        Delivery delivery;
        try {
            delivery = letters.republish(letter, targetGroup);
        } catch (RuntimeException e) {
            log.error("재발행 어댑터가 예외를 냈다 — 보냈는지 모른다", e);
            delivery = new Delivery.Unknown(e.getClass().getName());
        }
        return switch (delivery) {
            case Delivery.Acked _ -> new Outcome(AuditResult.SUCCEEDED, null);
            case Delivery.Refused refused -> new Outcome(AuditResult.FAILED, refused.detail());
            case Delivery.Unknown unknown -> new Outcome(AuditResult.UNKNOWN, unknown.detail());
        };
    }

    private void close(UUID auditId, Outcome outcome) {
        try {
            audit.close(auditId, outcome.result());
        } catch (RuntimeException e) {
            log.error("감사 결과를 쓰지 못했다 — 행은 PENDING 으로 남는다(다시 누르면 된다, RB-05) result={}",
                    outcome.result(), e);
            return;
        }
        Counter.builder(OpsCommandService.COMMANDS)
                .description("운영자 커맨드 — 감사 행의 결과를 커밋한 뒤에 센다 (DESIGN.md §9.1)")
                .tag("action", ACTION)
                .tag("result", outcome.result().name())
                .register(registry)
                .increment();
        if (outcome.result() == AuditResult.UNKNOWN) {
            log.warn("재처리를 보냈는지 모른다 — 그대로 다시 누르면 된다(RB-05) detail={}", outcome.detail());
        }
    }

    /** 읽고 가린 결과. */
    private sealed interface Lookup {

        @Nullable UUID eventId();

        record Found(DeadLetter letter) implements Lookup {
            @Override
            public @Nullable UUID eventId() {
                return letter.eventId();
            }
        }

        record Refused(@Nullable UUID eventId, String reason) implements Lookup {
        }

        record Unreadable(String detail) implements Lookup {
            @Override
            public @Nullable UUID eventId() {
                return null;
            }
        }
    }

    private record Outcome(AuditResult result, @Nullable String detail) {
    }
}
