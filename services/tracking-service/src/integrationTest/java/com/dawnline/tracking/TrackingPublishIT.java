package com.dawnline.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import org.awaitility.Awaitility;

import com.dawnline.common.Ids;
import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.messaging.outbox.OutboxRelay;
import com.dawnline.messaging.outbox.RelayLeadership;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.AssignedStop;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.RouteAssignment;
import com.dawnline.tracking.application.port.in.RecordScanUseCase;
import com.dawnline.tracking.application.port.in.RecordScanUseCase.ScanCommand;
import com.dawnline.tracking.domain.ScanType;
import com.redis.testcontainers.RedisContainer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;

/**
 * tracking 의 발행이 브로커까지 가서 계약을 지키는가 (§4.2·§4.4, 불변규칙 1·8).
 *
 * <p>Phase 1 의 {@code OrderPublishIT} 가 규칙으로 삼은 빈 칸을 이 서비스에서 메운다 — 릴레이
 * 자체는 {@code libs/messaging} 의 IT 가 보고 페이로드 모양은 단위 테스트가 보는데,
 * <em>이 서비스의 발행이 실제로 브로커에 도착해 봉투까지 계약을 지키는가</em>는 그 둘 사이에
 * 끼어 아무도 보지 않는다(다른 IT 들은 릴레이를 꺼 둔다).
 *
 * <h2>팬아웃을 함께 기록한다</h2>
 * Phase 5-5 가 dispatch 에 {@code delivery.status} 소비자를 하나 더 붙이고 그때 처리량을 다시
 * 잰다(Phase 4-0 이 건 조건, 기준 1,638건/초). 그 재측정의 <strong>입력</strong>이 「스캔 하나가
 * 이벤트 몇 건을 만드는가」이고, 그 비율은 여기서만 실제로 관측된다. 그래서 이 IT 가 수치를
 * 어설션으로 못 박는다 — 문서에 적어 두면 코드가 바뀔 때 함께 바뀌지 않는다.
 *
 * <p>여기서만 릴레이를 켠다. Kafka·Redis 도 여기서만 띄운다 — 기반 클래스는 이 속성에 의견을
 * 갖지 않는다(CLAUDE.md 「공유 자원을 쓰는 IT 는 자기 자리에서 켜고 끈다」).
 */
@SpringBootTest(classes = TrackingApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("TrackingPublishIT — outbox → 릴레이 → 브로커 → 계약")
class TrackingPublishIT extends TrackingIntegrationTestBase {

    private static final String STATUS_TOPIC = "dawnline.delivery.status.v1";
    private static final String AT_RISK_TOPIC = "dawnline.delivery.at-risk.v1";
    private static final String ROUTE_ASSIGNED_TOPIC = "dawnline.route.assigned.v1";

    private static final EventContracts CONTRACTS = EventContracts.load();

    /** deploy/compose/.env.example 의 태그와 같다. */
    private static final String KAFKA_IMAGE = "apache/kafka:4.3.1";
    private static final String REDIS_IMAGE = "redis:8.8.2";

    private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    private static final RedisContainer REDIS = new RedisContainer(REDIS_IMAGE);

    private static KafkaConsumer<String, String> consumer;

    static {
        KAFKA.start();
        REDIS.start();
        createTopics(STATUS_TOPIC, AT_RISK_TOPIC, ROUTE_ASSIGNED_TOPIC);
    }

    @Autowired
    private ApplyRouteAssignmentUseCase applyRouteAssignment;

    @Autowired
    private RecordScanUseCase recordScan;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private RelayLeadership leadership;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @Autowired
    private com.dawnline.tracking.application.port.out.EventPartitions partitions;

    private TransactionTemplate transactions;
    private UUID routeId;
    private Instant departure;
    private Instant firstArrival;

    /**
     * 이 IT 는 발행을 보므로 릴레이를 <strong>켠다</strong>. 파티션 스케줄러는 끈다 — 이
     * 클래스의 관심이 아니고, 켜 두면 기동마다 DDL 이 돈다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void 공유_자원을_켠다(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("dawnline.messaging.outbox.enabled", () -> "true");
        registry.add("dawnline.tracking.partitions.enabled", () -> "false");
        // 자기 발행을 자기가 소비하지 않는다. route.assigned 는 유스케이스로 직접 넣는다.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @BeforeAll
    static void subscribe() {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "tracking-publish-it-" + Ids.newId(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        consumer.subscribe(List.of(STATUS_TOPIC, AT_RISK_TOPIC));
    }

    @AfterAll
    static void unsubscribe() {
        consumer.close();
    }

    /**
     * <strong>전제 둘: 릴레이가 돌고, 이 세션이 리더다.</strong> 릴레이가 꺼져 있거나 다른
     * 컨텍스트가 advisory lock 을 쥐고 있으면 이 클래스는 아무것도 증명하지 못한 채 기다리다
     * 실패한다(ADR-027 후속 정정, §13 축 3·8).
     */
    @BeforeEach
    void 전제_릴레이가_돌고_이_세션이_리더다() {
        assertThat(relay).as("릴레이 빈이 없으면 발행 경로가 통째로 없다").isNotNull();
        assertThat(leadership.lead())
                .as("FOLLOWER 면 여기서는 한 행도 나가지 않는다 (ADR-027)")
                .isEqualTo(RelayLeadership.State.LEADER);

        transactions = new TransactionTemplate(transactionManager);
        routeId = Ids.newId();
        // 시각 리터럴을 쓰지 않는다 — 이 값들은 스캔 시각과 비교되어 편차가 된다.
        departure = clock.instant().plus(Duration.ofMinutes(5));
        firstArrival = clock.instant().plus(Duration.ofMinutes(30));
    }


    /**
     * <strong>전제: 오늘 쓸 파티션이 있다.</strong> {@code shipment_events} 에는 DEFAULT 파티션이
     * 없어서(§5.4) 범위 밖 INSERT 는 그 자리에서 실패한다 — 그것이 설계다. 마이그레이션이 기동
     * 시점에 창을 만들어 두지만, <em>같은 컨테이너를 쓰는 다른 IT 가 그것을 지울 수 있다</em>:
     * {@code ShipmentEventPartitionIT} 의 회전 검사는 2035년 기준 시계로 {@code rotate()} 를
     * 부르고, 그 회전은 보존 경계(기준일 − 30일)보다 앞선 파티션을 <strong>전부 드롭한다</strong> —
     * 오늘 것을 포함해서.
     *
     * <p>그래서 쓰는 쪽이 자기 자리에서 만든다. 클래스 실행 순서에 기대지 않는다(§13 여덟째 축) —
     * 순서가 근거이면 그 통과는 통과가 아니다. {@code ensure} 는 멱등이라 여러 번 불러도 된다.
     */
    @BeforeEach
    void 전제_오늘_쓸_파티션을_만든다() {
        partitions.ensure(LocalDate.now(ZoneOffset.UTC).minusDays(1), 3);
    }

    @Test
    void 완료_스캔이_브로커에_도착하고_봉투까지_계약을_지킨다() {
        UUID orderId = Ids.newId();
        assign(stop(1, List.of(orderId), firstArrival, farPromise()));

        scan(1, ScanType.COMPLETED, firstArrival);

        ConsumerRecord<String, String> record = awaitOne(STATUS_TOPIC);
        CONTRACTS.validateRecord(record.value());

        JsonNode envelope = CONTRACTS.json().readTree(record.value());
        assertThat(envelope.get("eventType").asString()).isEqualTo("delivery.status");
        assertThat(envelope.get("producer").asString()).isEqualTo("tracking-service");

        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(payload.get("stopSeq").intValue()).isEqualTo(1);
        assertThat(payload.get("orderIds").get(0).asString()).isEqualTo(orderId.toString());
        // §4.1 — 같은 라우트의 진행 상태가 순서대로 온다는 것이 이 키 선택의 목적이다.
        assertThat(record.key()).isEqualTo(routeId.toString());
    }

    @Test
    void 팬아웃은_스캔당_한_건이고_캠프_출발은_0_이다() {
        // Phase 5-5 처리량 재측정의 입력이다. 라우트 하나(stop 3)를 끝까지 몰아 보고 실제
        // 비율을 센다 — DEPARTED_CAMP 1회 + (ARRIVED + COMPLETED) × 3.
        int stopCount = 3;
        List<AssignedStop> stops = new ArrayList<>(stopCount);
        for (int seq = 1; seq <= stopCount; seq++) {
            stops.add(stop(seq, List.of(Ids.newId()),
                    firstArrival.plus(Duration.ofMinutes(10L * (seq - 1))), farPromise()));
        }
        assign(stops.toArray(AssignedStop[]::new));

        scan(1, ScanType.DEPARTED_CAMP, departure);
        for (int seq = 1; seq <= stopCount; seq++) {
            Instant at = firstArrival.plus(Duration.ofMinutes(10L * (seq - 1)));
            scan(seq, ScanType.ARRIVED, at);
            scan(seq, ScanType.COMPLETED, at.plus(Duration.ofMinutes(2)));
        }

        List<ConsumerRecord<String, String>> records = await(STATUS_TOPIC, 2 * stopCount);

        assertThat(records)
                .as("스캔 7회(DEPARTED_CAMP 1 + 상태 6) → delivery.status 6건. "
                        + "캠프 출발은 라우트의 사건이라 브로커로 나가지 않는다 — 나가면 "
                        + "stop 수만큼(여기서는 3건) 같은 사실이 반복된다")
                .hasSize(2 * stopCount);
        assertThat(records).allSatisfy(record -> {
            CONTRACTS.validateRecord(record.value());
            JsonNode payload = CONTRACTS.json().readTree(record.value()).get("payload");
            assertThat(payload.get("status").asString()).isNotEqualTo("DEPARTED_CAMP");
            assertThat(payload.get("orderIds"))
                    .as("stop 단위 한 건이고 orderIds 가 그 stop 의 주문을 든다")
                    .hasSize(1);
        });
    }

    @Test
    void 늦은_출발이_at_risk_를_만들고_쿨다운이_두_번째를_막는다() {
        // 약속 끝이 첫 도착 20분 뒤다 — 출발이 60분 늦으면 전 stop 이 여유(15분) 안으로 들어온다.
        Instant promisedEnd = firstArrival.plus(Duration.ofMinutes(20));
        assign(stop(1, List.of(Ids.newId()), firstArrival, promisedEnd),
                stop(2, List.of(Ids.newId()), firstArrival.plus(Duration.ofMinutes(10)),
                        promisedEnd.plus(Duration.ofMinutes(10))));

        scan(1, ScanType.DEPARTED_CAMP, departure.plus(Duration.ofMinutes(60)));

        ConsumerRecord<String, String> record = awaitOne(AT_RISK_TOPIC);
        CONTRACTS.validateRecord(record.value());

        JsonNode payload = CONTRACTS.json().readTree(record.value()).get("payload");
        assertThat(payload.get("routeId").asString()).isEqualTo(routeId.toString());
        assertThat(payload.get("deviationSeconds").longValue())
                .as("이 판정을 부른 스캔의 편차다")
                .isEqualTo(Duration.ofMinutes(60).toSeconds());
        assertThat(payload.get("remainingStops"))
                .as("위험한 것만이 아니라 남은 구간 전부다 — 재계획의 입력이다")
                .hasSize(2);
        assertThat(record.key()).isEqualTo(routeId.toString());

        // 같은 라우트에 또 위험이 와도 창이 열려 있는 동안은 한 번만 말한다 (§7.2, ADR-046).
        scan(1, ScanType.ARRIVED, firstArrival.plus(Duration.ofMinutes(60)));

        assertThat(poll(AT_RISK_TOPIC, Duration.ofSeconds(3)))
                .as("쿨다운이 지키는 것은 알림 수다 — 재계획 중복은 dispatch 의 DB 가 막는다")
                .isEmpty();
    }

    // --- 픽스처 --------------------------------------------------------------

    /** 약속 끝을 멀리 둔다 — 이 테스트에서 at-risk 는 관심이 아니다. */
    private Instant farPromise() {
        return firstArrival.plus(Duration.ofHours(6));
    }

    private AssignedStop stop(int seq, List<UUID> orderIds, Instant arrival, Instant promisedEnd) {
        return new AssignedStop(seq, orderIds, Set.of(), arrival, promisedEnd);
    }

    private void assign(AssignedStop... stops) {
        transactions.executeWithoutResult(status -> applyRouteAssignment.apply(
                new RouteAssignment(routeId, 1, Ids.newId(), departure, List.of(stops))));
    }

    private void scan(int seq, ScanType type, Instant at) {
        recordScan.record(new ScanCommand(routeId, seq, type, at, null, null, null));
    }

    private ConsumerRecord<String, String> awaitOne(String topic) {
        return await(topic, 1).getFirst();
    }

    private List<ConsumerRecord<String, String>> await(String topic, int count) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    found.addAll(poll(topic, Duration.ofMillis(200)));
                    assertThat(found).hasSizeGreaterThanOrEqualTo(count);
                });
        return List.copyOf(found);
    }

    /**
     * 이 테스트의 <strong>라우트</strong>에 속한 레코드만 고른다.
     *
     * <p>토픽은 같은 JVM 의 다른 IT 들과 공유된다. 그리고 릴레이를 <em>여기서만</em> 켜므로,
     * 다른 IT 가 outbox 에 남긴 행도 이 컨텍스트가 뜨는 순간 함께 나간다 — 발행을 끈 IT 는
     * 발행을 안 한 것이 아니라 <em>미룬</em> 것이다. 키가 {@code routeId} 라(§4.1) 그것으로
     * 가른다. 「내 것만 센다」를 쓰지 않으면 팬아웃 어설션이 남의 스캔까지 세고, 그 실패는
     * 실행 순서에 따라 나타났다 사라진다(§13 여덟째 축).
     */
    private List<ConsumerRecord<String, String>> poll(String topic, Duration timeout) {
        ConsumerRecords<String, String> records = consumer.poll(timeout);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        records.records(topic).forEach(record -> {
            if (routeId.toString().equals(record.key())) {
                found.add(record);
            }
        });
        return found;
    }

    private static void createTopics(String... names) {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(names).stream()
                    .map(name -> new NewTopic(name, 1, (short) 1)).toList()).all().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("테스트 토픽을 만들지 못했습니다", e);
        }
    }
}
