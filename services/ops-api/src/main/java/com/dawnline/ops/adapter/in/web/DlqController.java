package com.dawnline.ops.adapter.in.web;

import com.dawnline.ops.application.port.in.ReplayDeadLettersUseCase;
import com.dawnline.ops.application.port.in.ReplayDeadLettersUseCase.RecordRef;
import com.dawnline.ops.application.port.in.ReplayDeadLettersUseCase.Replayed;
import com.dawnline.ops.application.port.out.DeadLetters.DeadLetter;
import com.dawnline.ops.domain.AuditResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DLQ 목록과 재처리 (DESIGN.md §4.6 「DLQ 재처리」, ADR-053).
 *
 * <p>목록 → 운영자가 고른 것만 재처리. 그것이 §4.6 의 「운영자 확인 후」다. 권한은 {@code SecurityConfig} 의 메서드
 * 규칙 그대로({@code GET} 은 {@code OPS_VIEWER}, {@code POST} 는 {@code OPS_OPERATOR} 이상).
 *
 * <p><strong>목록은 value 도 예외 메시지도 싣지 않는다</strong>(§9.3) — 둘 다 주소를 담을 수 있다. 위치와 이름뿐이다.
 *
 * <p>재처리의 답은 언제나 200 이고 레코드마다 결과가 있다 — 한 요청 안에서 어떤 것은 나가고 어떤 것은 거절될 수 있다.
 * 요청 자체가 틀렸으면(계약에 없는 토픽·빈 목록·100개 초과) 4xx 이고 아무것도 기록하지 않는다.
 */
@RestController
@RequestMapping(path = "/api/{version}/admin/dlq", version = "1")
public class DlqController {

    private final ReplayDeadLettersUseCase dlq;

    public DlqController(ReplayDeadLettersUseCase dlq) {
        this.dlq = Objects.requireNonNull(dlq, "dlq");
    }

    /**
     * @param topic 원래 토픽 — 계약에 있어야 한다
     * @param limit 최근 것부터 몇 개(1–500)
     * @return 위치와 이름뿐인 목록
     */
    @GetMapping("/{topic}")
    public DeadLetterList list(@PathVariable String topic, @RequestParam(defaultValue = "50") int limit) {
        return new DeadLetterList(topic, dlq.list(topic, limit).stream().map(DeadLetterView::of).toList());
    }

    /**
     * @param topic    원래 토픽 — 계약에 있어야 한다
     * @param body     고른 레코드
     * @param operator 감사 행의 {@code actor} 는 토큰의 {@code sub}
     * @return 레코드마다 감사 id 와 결과
     */
    @PostMapping("/{topic}/replay")
    public ReplayResponse replay(@PathVariable String topic, @Valid @RequestBody ReplayBody body,
            @AuthenticationPrincipal Jwt operator) {
        List<RecordRef> records = body.records().stream()
                .map(r -> new RecordRef(Objects.requireNonNull(r.partition()), Objects.requireNonNull(r.offset())))
                .toList();
        return new ReplayResponse(topic,
                dlq.replay(operator.getSubject(), topic, records).stream().map(ReplayResult::of).toList());
    }

    /**
     * @param topic   원래 토픽
     * @param records 최근 것부터
     */
    public record DeadLetterList(String topic, List<DeadLetterView> records) {
    }

    /**
     * DLQ 레코드 하나 — value 는 없다.
     *
     * @param partition         DLQ 의 파티션 — 재처리할 때 이것을 고른다
     * @param offset            DLQ 의 오프셋
     * @param timestamp         DLQ 에 들어간 레코드의 시각
     * @param eventType         이벤트 타입
     * @param eventId           이벤트 id — 감사 행의 {@code target_id} 가 된다
     * @param originalGroup     실패한 소비자 그룹 — 재처리가 지목할 그룹. 없으면 재처리할 수 없다
     * @param originalPartition 원래 파티션
     * @param originalOffset    원래 오프셋
     * @param exceptionClass    원인 예외의 클래스 이름
     */
    public record DeadLetterView(int partition, long offset, Instant timestamp, @Nullable String eventType,
            @Nullable UUID eventId, @Nullable String originalGroup, @Nullable Integer originalPartition,
            @Nullable Long originalOffset, @Nullable String exceptionClass) {

        static DeadLetterView of(DeadLetter letter) {
            return new DeadLetterView(letter.partition(), letter.offset(), letter.timestamp(), letter.eventType(),
                    letter.eventId(), letter.originalGroup(), letter.originalPartition(), letter.originalOffset(),
                    letter.exceptionClass());
        }
    }

    /**
     * @param records 고른 레코드 — 1–100개
     */
    public record ReplayBody(@NotEmpty @Size(max = ReplayDeadLettersUseCase.MAX_RECORDS) List<@Valid Pick> records) {
        public ReplayBody {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }

    /**
     * @param partition DLQ 의 파티션
     * @param offset    DLQ 의 오프셋
     */
    public record Pick(@NotNull @PositiveOrZero @Nullable Integer partition,
            @NotNull @PositiveOrZero @Nullable Long offset) {
    }

    /**
     * @param topic   원래 토픽
     * @param results 준 순서대로
     */
    public record ReplayResponse(String topic, List<ReplayResult> results) {
    }

    /**
     * @param partition DLQ 의 파티션
     * @param offset    DLQ 의 오프셋
     * @param auditId   감사 행
     * @param eventId   재처리한 이벤트
     * @param result    감사 결과
     * @param detail    {@code SUCCEEDED} 가 아니면 이유
     */
    public record ReplayResult(int partition, long offset, UUID auditId, @Nullable UUID eventId, AuditResult result,
            @Nullable String detail) {

        static ReplayResult of(Replayed replayed) {
            return new ReplayResult(replayed.record().partition(), replayed.record().offset(), replayed.auditId(),
                    replayed.eventId(), replayed.result(), replayed.detail());
        }
    }
}
