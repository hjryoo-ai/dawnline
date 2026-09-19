package com.dawnline.sim.driver;

import com.dawnline.sim.order.Sleeper;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Spring 의 리스너 컨테이너로 {@code route.assigned} 를 받는 {@link RouteFeed}.
 *
 * <h2>켜는 것만으로는 부족하다 — 배정까지 기다린다</h2>
 * {@code container.start()} 는 비동기다. 컨슈머가 그룹에 들어가 파티션을 배정받기 전에 주문이
 * 흘러가면 그 사이에 확정된 라우트를 <strong>영영 못 본다</strong>({@code auto-offset-reset:
 * latest} 이므로 되감기도 없다). 그 결과는 「라우트가 안 왔다」로 나타나고, 그것은 웨이브가
 * 안 닫힌 것과 구별되지 않는다. 그래서 배정을 확인하고 돌아온다.
 */
public final class KafkaRouteFeed implements RouteFeed {

    private static final Logger log = LoggerFactory.getLogger(KafkaRouteFeed.class);

    /** 배정을 확인하는 간격. */
    private static final long POLL_INTERVAL_NANOS = 100_000_000L;

    private final KafkaListenerEndpointRegistry registry;
    private final String listenerId;
    private final Duration assignmentTimeout;
    private final Sleeper sleeper;
    private final LongSupplier nanoTime;

    /**
     * @param registry          리스너 레지스트리. 컨테이너는 {@link #open()} 에서 찾는다 —
     *                          엔드포인트 등록은 컨텍스트 refresh 의 마지막에 일어나므로
     *                          배선 시점에 찾으면 아직 없다
     * @param listenerId        컨테이너 id
     * @param assignmentTimeout 파티션 배정을 기다리는 상한
     * @param sleeper           대기
     * @param nanoTime          단조 시계
     */
    public KafkaRouteFeed(KafkaListenerEndpointRegistry registry, String listenerId, Duration assignmentTimeout,
            Sleeper sleeper, LongSupplier nanoTime) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.listenerId = Objects.requireNonNull(listenerId, "listenerId");
        this.assignmentTimeout = Objects.requireNonNull(assignmentTimeout, "assignmentTimeout");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public void open() {
        container().start();
        long deadline = nanoTime.getAsLong() + assignmentTimeout.toNanos();
        while (!assigned()) {
            if (nanoTime.getAsLong() >= deadline) {
                throw new IllegalStateException("""
                        %d초 안에 %s 의 파티션을 배정받지 못했다. \
                        브로커가 떠 있는지(spring.kafka.bootstrap-servers)와 토픽이 있는지 확인한다. \
                        배정 전에 시나리오를 시작하면 그 사이의 라우트를 영영 놓친다."""
                        .formatted(assignmentTimeout.toSeconds(), RouteAssignedListener.ROUTE_ASSIGNED_TOPIC));
            }
            sleep();
        }
        log.info("{} 수신 시작. 배정된 파티션 {}개",
                RouteAssignedListener.ROUTE_ASSIGNED_TOPIC, partitions().size());
    }

    private boolean assigned() {
        return !partitions().isEmpty();
    }

    private Collection<TopicPartition> partitions() {
        Collection<TopicPartition> assigned = container().getAssignedPartitions();
        return assigned == null ? List.of() : assigned;
    }

    private MessageListenerContainer container() {
        MessageListenerContainer found = registry.getListenerContainer(listenerId);
        if (found == null) {
            throw new IllegalStateException(
                    "리스너 컨테이너 '%s' 가 없습니다. @KafkaListener 의 id 와 같아야 합니다".formatted(listenerId));
        }
        return found;
    }

    private void sleep() {
        try {
            sleeper.sleepNanos(POLL_INTERVAL_NANOS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("파티션 배정을 기다리다 중단되었다", exception);
        }
    }

    @Override
    public void close() {
        container().stop();
    }
}
