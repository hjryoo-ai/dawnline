package com.dawnline.ops.application.port.in;

import com.dawnline.ops.application.port.out.DeadLetters.DeadLetter;
import com.dawnline.ops.domain.AuditResult;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * DLQ 를 보고, 운영자가 고른 것만 원래 그룹에게 다시 보낸다 (DESIGN.md §4.6 「DLQ 재처리」, ADR-053).
 *
 * <p>계약에 없는 토픽이면 {@code DomainException}({@code not-found})이고 아무것도 기록하지 않는다 — 시도한 것이 없다.
 * 감사 행을 쓰지 못하면 {@code DomainException}({@code unavailable}) — 그 앞의 레코드는 이미 제 행이 있고, 재처리는
 * 멱등이므로 같은 요청을 그대로 다시 보내면 된다.
 */
public interface ReplayDeadLettersUseCase {

    /** 한 요청에 담을 수 있는 레코드 수. */
    int MAX_RECORDS = 100;

    /** 목록의 최대 길이. */
    int MAX_LISTED = 500;

    /**
     * @param topic 원래 토픽 — 계약에 있어야 한다
     * @param limit 1 이상 {@link #MAX_LISTED} 이하
     * @return 최근 것부터
     */
    List<DeadLetter> list(String topic, int limit);

    /**
     * @param actor   JWT 의 {@code sub}
     * @param topic   원래 토픽 — 계약에 있어야 한다
     * @param records 1 개 이상 {@link #MAX_RECORDS} 개 이하
     * @return 레코드마다 하나, 준 순서대로
     */
    List<Replayed> replay(String actor, String topic, List<RecordRef> records);

    /**
     * @param partition DLQ 의 파티션
     * @param offset    DLQ 의 오프셋
     */
    record RecordRef(int partition, long offset) {
    }

    /**
     * @param record  고른 레코드
     * @param auditId 감사 행
     * @param eventId 재처리한 이벤트 — 레코드를 읽지 못했거나 value 에 없으면 비어 있다
     * @param result  {@code PENDING} 이 아닌 결과
     * @param detail  {@code SUCCEEDED} 가 아니면 이유(코드 또는 예외 클래스 이름)
     */
    record Replayed(RecordRef record, UUID auditId, @Nullable UUID eventId, AuditResult result,
            @Nullable String detail) {
        public Replayed {
            Objects.requireNonNull(record, "record");
            Objects.requireNonNull(auditId, "auditId");
            Objects.requireNonNull(result, "result");
        }
    }
}
