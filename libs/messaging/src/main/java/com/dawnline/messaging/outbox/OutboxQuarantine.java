package com.dawnline.messaging.outbox;

import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.common.error.ValidationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 격리 행의 조회와 재큐 (DESIGN.md §4.6 「격리 조회·재큐 엔드포인트」, ADR-015 후속 정정, RB-05).
 *
 * <p>RB-05 의 복구는 두 절반이다 — 원인 수정과 격리 해제. 여기 있는 것은 <strong>뒤의 절반</strong>뿐이다.
 * <strong>재큐는 원인을 고치지 않는다</strong>: 원인이 남아 있으면 릴레이가 다시 집어 다시 격리한다.
 *
 * <p>로그는 커밋 <em>뒤에</em> 남긴다. 트랜잭션 안에서 「풀었다」고 적으면 커밋이 실패한 날 로그가 거짓을 말한다
 * (CLAUDE.md 「카운터는 커밋 뒤에 센다」와 같은 축).
 */
public class OutboxQuarantine {

    /** 목록의 기본 크기. */
    public static final int DEFAULT_LIMIT = 50;

    /** 목록의 상한 — DLQ 목록(ops-api)과 같은 값이다. 격리 행은 평상시 0 이라 이 상한이 닿는 날은 이미 사건이다. */
    public static final int MAX_LIMIT = 500;

    private static final Logger log = LoggerFactory.getLogger(OutboxQuarantine.class);

    private final OutboxRepository repository;
    private final TransactionTemplate transactions;

    /**
     * @param repository         outbox 저장소
     * @param transactionManager 이 서비스 DB 의 트랜잭션 관리자
     */
    public OutboxQuarantine(OutboxRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /**
     * @param limit 최대 행 수 (1–{@value #MAX_LIMIT})
     * @return 전체 수와 격리 시각 순 목록
     * @throws ValidationException {@code limit} 이 범위 밖일 때
     */
    public QuarantinedOutboxEvents list(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ValidationException.field("limit", limit, "1 이상 " + MAX_LIMIT + " 이하여야 합니다");
        }
        // total 과 목록은 두 문장이다. READ COMMITTED 라 그 사이에 격리가 하나 늘면 total 이 목록보다 하나 클 수
        // 있다 — total 은 「잘렸는가」를 말하는 칸이고, 그 차이는 다음 조회가 메운다.
        return Objects.requireNonNull(transactions.execute(status ->
                new QuarantinedOutboxEvents(repository.countFailed(), repository.findQuarantined(limit))));
    }

    /**
     * 격리를 푼다.
     *
     * @param id 행 id
     * @return 풀린 행
     * @throws NotFoundException 행이 없을 때
     * @throws DomainException   {@code not-quarantined} — 격리된 행이 아니다. 본문의 {@code currentState} 가 지금
     *                           위치({@code PENDING}·{@code PUBLISHED})를 말한다
     */
    public RequeuedOutboxEvent requeue(UUID id) {
        Objects.requireNonNull(id, "id");
        Outcome outcome = Objects.requireNonNull(transactions.execute(status -> releaseInTransaction(id)));
        return switch (outcome) {
            case Outcome.Released released -> {
                // 커밋 뒤다 — transactions.execute 가 돌아왔다.
                log.info("outbox 격리를 풀었습니다. 원인이 남아 있으면 다시 격리됩니다. eventId={} eventType={} topic={}",
                        released.row().id(), released.row().eventType(), released.row().topic());
                yield released.row();
            }
            case Outcome.NotFound _ -> throw NotFoundException.of("OutboxEvent", id);
            case Outcome.NotQuarantined notQuarantined -> throw notQuarantined(notQuarantined.row());
        };
    }

    private Outcome releaseInTransaction(UUID id) {
        // 풀기가 먼저다 — 조건부 UPDATE 한 문장이 판정이다. 0 행일 때만 다시 읽어 이유를 가른다.
        if (repository.releaseQuarantine(id)) {
            OutboxEvent row = repository.findById(id).orElseThrow(() ->
                    new IllegalStateException("방금 푼 outbox 행을 다시 읽지 못했습니다: id=" + id));
            return new Outcome.Released(RequeuedOutboxEvent.of(row));
        }
        return repository.findById(id)
                .<Outcome>map(Outcome.NotQuarantined::new)
                .orElseGet(Outcome.NotFound::new);
    }

    /**
     * 409 의 본문 — 다시 누른 사람이 읽을 사실.
     *
     * <p>{@code PENDING} 이면 격리가 풀려 발행을 기다리고, {@code PUBLISHED} 면 이미 나갔다. 어느 쪽이든 앞의
     * 재큐가 적용됐다는 뜻이다(행이 처음부터 격리된 적이 없었던 경우를 빼면 — 그 id 는 목록에서 오지 않은 것이다).
     */
    private static DomainException notQuarantined(OutboxEvent row) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("currentState", row.isPublished() ? "PUBLISHED" : "PENDING");
        row.publishedAt().ifPresent(at -> details.put("publishedAt", at.toString()));
        return new DomainException(OutboxErrorCode.NOT_QUARANTINED,
                "격리된 outbox 행이 아닙니다: " + row.id(), details);
    }

    /** 한 트랜잭션 안에서 정해진 결과. 로그와 예외는 커밋 뒤에 이것을 보고 낸다. */
    private sealed interface Outcome {

        record Released(RequeuedOutboxEvent row) implements Outcome {
        }

        record NotQuarantined(OutboxEvent row) implements Outcome {
        }

        record NotFound() implements Outcome {
        }
    }
}
