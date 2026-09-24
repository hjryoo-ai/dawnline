package com.dawnline.ops.adapter.out.core;

import com.dawnline.ops.application.port.out.CoreReply;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;

/**
 * 코어 넷의 outbox 관리 클라이언트를 한 모양으로 (DESIGN.md §4.6 · §5.5).
 *
 * <p>같은 공유 코드(`libs/messaging`)가 코어 넷에 같은 경로·같은 스키마를 만들었지만, 생성기는 문서마다 자기
 * 패키지에 모델을 만든다 — {@code order.model.RequeuedOutboxEvent} 와 {@code dispatch.model.RequeuedOutboxEvent} 는
 * 칸이 같은 다른 타입이다. 모델을 한 패키지로 모으는 생성기 옵션(schemaMappings)은 쓰지 않았다: 한 문서의 스키마가
 * 바뀌면 그 코어의 클라이언트만 깨져야 하는데, 모으면 넷이 한 타입에 묶인다. 대신 옮기는 자리가 넷이고, 넷은 이
 * 파일 안에서 같은 두 함수({@link #requeued}·{@link #event})로 모인다.
 */
final class OutboxAdminClients {

    private OutboxAdminClients() {
    }

    /** 한 코어의 outbox 관리 — 칸을 {@link CoreReply} 의 몸으로 옮긴 뒤의 모양. */
    interface OutboxAdmin {

        /** @return 풀린 행. 2xx 인데 칸이 비면 {@link RestClientException} */
        CoreReply.OutboxRequeued requeue(UUID id);

        /** @return 격리 목록. 2xx 인데 칸이 비면 {@link RestClientException} */
        CoreReply.QuarantinedOutbox list(@Nullable Integer limit);
    }

    static OutboxAdmin order(com.dawnline.ops.adapter.out.core.order.api.OutboxAdminControllerApi api) {
        return new OutboxAdmin() {
            @Override
            public CoreReply.OutboxRequeued requeue(UUID id) {
                var body = required(api.requeue(id));
                return requeued(body.getId(), body.getAggregateType(), body.getAggregateId(), body.getEventType(),
                        body.getTopic());
            }

            @Override
            public CoreReply.QuarantinedOutbox list(@Nullable Integer limit) {
                var body = required(api.listQuarantined(limit));
                return listed(body.getTotal(), body.getEvents(), e -> event(e.getId(), e.getAggregateType(),
                        e.getAggregateId(), e.getEventType(), e.getTopic(), e.getCreatedAt(), e.getFailedAt(),
                        e.getPublishAttempts()));
            }
        };
    }

    static OutboxAdmin fulfillment(com.dawnline.ops.adapter.out.core.fulfillment.api.OutboxAdminControllerApi api) {
        return new OutboxAdmin() {
            @Override
            public CoreReply.OutboxRequeued requeue(UUID id) {
                var body = required(api.requeue(id));
                return requeued(body.getId(), body.getAggregateType(), body.getAggregateId(), body.getEventType(),
                        body.getTopic());
            }

            @Override
            public CoreReply.QuarantinedOutbox list(@Nullable Integer limit) {
                var body = required(api.listQuarantined(limit));
                return listed(body.getTotal(), body.getEvents(), e -> event(e.getId(), e.getAggregateType(),
                        e.getAggregateId(), e.getEventType(), e.getTopic(), e.getCreatedAt(), e.getFailedAt(),
                        e.getPublishAttempts()));
            }
        };
    }

    static OutboxAdmin dispatch(com.dawnline.ops.adapter.out.core.dispatch.api.OutboxAdminControllerApi api) {
        return new OutboxAdmin() {
            @Override
            public CoreReply.OutboxRequeued requeue(UUID id) {
                var body = required(api.requeue(id));
                return requeued(body.getId(), body.getAggregateType(), body.getAggregateId(), body.getEventType(),
                        body.getTopic());
            }

            @Override
            public CoreReply.QuarantinedOutbox list(@Nullable Integer limit) {
                var body = required(api.listQuarantined(limit));
                return listed(body.getTotal(), body.getEvents(), e -> event(e.getId(), e.getAggregateType(),
                        e.getAggregateId(), e.getEventType(), e.getTopic(), e.getCreatedAt(), e.getFailedAt(),
                        e.getPublishAttempts()));
            }
        };
    }

    static OutboxAdmin tracking(com.dawnline.ops.adapter.out.core.tracking.api.OutboxAdminControllerApi api) {
        return new OutboxAdmin() {
            @Override
            public CoreReply.OutboxRequeued requeue(UUID id) {
                var body = required(api.requeue(id));
                return requeued(body.getId(), body.getAggregateType(), body.getAggregateId(), body.getEventType(),
                        body.getTopic());
            }

            @Override
            public CoreReply.QuarantinedOutbox list(@Nullable Integer limit) {
                var body = required(api.listQuarantined(limit));
                return listed(body.getTotal(), body.getEvents(), e -> event(e.getId(), e.getAggregateType(),
                        e.getAggregateId(), e.getEventType(), e.getTopic(), e.getCreatedAt(), e.getFailedAt(),
                        e.getPublishAttempts()));
            }
        };
    }

    private static CoreReply.OutboxRequeued requeued(@Nullable UUID id, @Nullable String aggregateType,
            @Nullable UUID aggregateId, @Nullable String eventType, @Nullable String topic) {
        return new CoreReply.OutboxRequeued(present(id), present(aggregateType), present(aggregateId),
                present(eventType), present(topic));
    }

    private static <E> CoreReply.QuarantinedOutbox listed(@Nullable Long total, @Nullable List<E> events,
            Function<E, CoreReply.QuarantinedEvent> event) {
        return new CoreReply.QuarantinedOutbox(present(total), present(events).stream().map(event).toList());
    }

    private static CoreReply.QuarantinedEvent event(@Nullable UUID id, @Nullable String aggregateType,
            @Nullable UUID aggregateId, @Nullable String eventType, @Nullable String topic,
            @Nullable OffsetDateTime createdAt, @Nullable OffsetDateTime failedAt, @Nullable Integer publishAttempts) {
        return new CoreReply.QuarantinedEvent(present(id), present(aggregateType), present(aggregateId),
                present(eventType), present(topic), present(createdAt).toInstant(), present(failedAt).toInstant(),
                present(publishAttempts));
    }

    private static <T> T required(ResponseEntity<T> response) {
        return present(response.getBody());
    }

    /** 2xx 인데 칸이 비었다 — {@link CoreCommandsClient} 와 같은 규칙: 호출자가 「모름」으로 접는다. */
    private static <T> T present(@Nullable T value) {
        if (value == null) {
            throw new RestClientException("코어의 2xx 본문에 계약이 말한 칸이 없다");
        }
        return value;
    }
}
