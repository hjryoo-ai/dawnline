package com.dawnline.sim.driver;

import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.kafka.EventRecords;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * {@code route.assigned} 수신 — 기사에게 라우트를 넘긴다 (DESIGN.md §4.1, §5.6).
 *
 * <p>tracking 의 같은 이름 리스너와 <strong>다른 컨슈머 그룹</strong>으로 읽는다. 둘은 서로를
 * 기다리지 않으므로 이 도구가 먼저 읽는 일이 있고, 그때 스캔은 404 를 받는다 —
 * {@link ScanClient.Response#isNotYetKnown()} 참고.
 *
 * <p>{@code autoStartup=false} 인 이유는 시나리오다. {@code smoke} 처럼 기사를 쓰지 않는
 * 시나리오는 브로커 없이 돌아야 하고, 컨테이너가 자동으로 뜨면 그 순간 Kafka 가 필수가 된다.
 * 켜는 것은 {@link DriverScenario} 이고, 켜는 시점은 <strong>주문을 넣기 전</strong>이다 —
 * 뒤면 그 사이에 확정된 라우트를 놓친다.
 *
 * <p>봉투를 여는 것은 {@code libs/messaging} 이다. 토픽 이름만 여기 리터럴로 적는데
 * ({@code @KafkaListener} 의 {@code topics} 는 컴파일 타임 상수여야 한다) 그 값이
 * {@code Topics.forEvent} 와 같은지는 {@code RouteAssignedListenerTopicsTest} 가 대조한다 —
 * 오타는 <strong>조용히 아무것도 받지 않는</strong> 형태로 나타난다.
 */
public class RouteAssignedListener {

    /** 이 리스너 컨테이너의 id. {@link DriverScenario} 가 이 이름으로 켠다. */
    public static final String LISTENER_ID = "sim-driver";

    /** {@code Topics.forEvent("route.assigned", 1)} 와 같아야 한다. 테스트가 확인한다. */
    static final String ROUTE_ASSIGNED_TOPIC = "dawnline.route.assigned.v1";

    private static final Logger log = LoggerFactory.getLogger(RouteAssignedListener.class);

    private final DriverFleet fleet;
    private final EventJson json;

    /**
     * @param fleet 기사들
     * @param json  이벤트 JSON 코덱
     */
    public RouteAssignedListener(DriverFleet fleet, EventJson json) {
        this.fleet = Objects.requireNonNull(fleet, "fleet");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 라우트 확정·개정 하나.
     *
     * @param record Kafka 레코드
     */
    @KafkaListener(id = LISTENER_ID, topics = ROUTE_ASSIGNED_TOPIC, autoStartup = "false")
    public void onRouteAssigned(ConsumerRecord<String, String> record) {
        EventEnvelope<AssignedRoute> envelope = EventRecords.parse(json, record, AssignedRoute.class);
        AssignedRoute route = envelope.payload();
        log.debug("라우트를 받았다. routeId={}, revision={}, stops={}",
                route.routeId(), route.revision(), route.stops().size());
        fleet.assign(route);
    }
}
