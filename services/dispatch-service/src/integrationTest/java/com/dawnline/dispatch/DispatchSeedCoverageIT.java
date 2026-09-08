package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.dispatch.adapter.out.persistence.JdbcReferenceData;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 시드가 계약 파일과 갈라지지 않았는가.
 *
 * <h2>왜 필요한가</h2>
 * {@code contracts/seed/dispatch-rules.json} 은 <strong>두 곳</strong>이 쓴다 — 룰 엔진의 단위
 * 테스트({@code SeededRuleSetTest})와 벤치마크 하네스가 파일을 읽고,
 * {@code R__seed_dispatch.sql} 이 같은 내용을 DB 에 넣는다. 두 벌을 손으로 맞추면 갈라지고,
 * 갈라진 날 <em>"테스트는 통과하는데 운영 룰이 다르다"</em> 가 된다.
 *
 * <p>{@code contracts/seed/order-service-geohash5.txt} 를 양쪽 서비스가 각자 검사하는 것과 같은
 * 방식이다(ADR-021).
 */
@SpringBootTest(classes = DispatchApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("DispatchSeedCoverageIT — 시드와 계약 파일")
class DispatchSeedCoverageIT extends DispatchIntegrationTestBase {

    /**
     * 릴레이를 끈다 — 이 클래스는 발행을 보지 않는다.
     *
     * <p>끄는 것이 <strong>격리</strong>다. 리더 락이 advisory lock 이 된 뒤(ADR-027 후속 정정)
     * 이 컨테이너의 한 데이터베이스에 대해 릴레이는 <em>한 컨텍스트만</em> 리더가 된다. 스프링은
     * 컨텍스트를 캐시하므로 먼저 뜬 클래스의 릴레이가 락을 계속 쥐고, 그러면 실제로 발행을 보는
     * {@code PlanExecutionIT} 가 팔로워가 되어 아무것도 못 본다. 순서에 달린 실패다.
     *
     * <p>이전에는 이 문제가 보이지 않았다 — 리더 락이 Redis 였고 이 컨텍스트들에는 Redis 가
     * 없어서 전부 판정 불가(발행 안 함)였기 때문이다. <strong>격리가 락의 무력함에 기대고
     * 있었다.</strong>
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    private static final Path RULES_CONTRACT = Path.of("../../contracts/seed/dispatch-rules.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** fulfillment 의 {@code R__seed_fulfillment.sql} 이 쓰는 캠프 UUID 접두사. */
    private static final String CAMP_ID_PREFIX = "01a06edd-6c00-7000-8001-";

    @Autowired
    private JdbcReferenceData referenceData;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 전제_계약_파일이_존재한다() {
        assertThat(RULES_CONTRACT).as("경로가 어긋나면 아래 검사가 빈 목록끼리 비교해 통과한다")
                .exists();
    }

    @Test
    @Transactional
    void 시드된_룰이_계약_파일과_정확히_같다() {
        Map<String, Map<String, Object>> expected = new LinkedHashMap<>();
        for (Map<String, Object> rule : contractRules()) {
            expected.put((String) rule.get("name"), rule);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT name, type, severity, priority, params::text
                  FROM dispatch_rules WHERE camp_id IS NULL ORDER BY priority, name
                """).getResultList();

        assertThat(rows).as("전역 룰 수").hasSize(expected.size());
        for (Object[] row : rows) {
            String name = (String) row[0];
            Map<String, Object> rule = expected.get(name);
            assertThat(rule).as("계약에 없는 룰이 시드에 있다: %s", name).isNotNull();
            assertThat(row[1]).as("%s.type", name).isEqualTo(rule.get("type"));
            assertThat(row[2]).as("%s.severity", name).isEqualTo(rule.get("severity"));
            assertThat(((Number) row[3]).intValue()).as("%s.priority", name)
                    .isEqualTo(((Number) rule.get("priority")).intValue());
            assertThat(JSON.readTree((String) row[4])).as("%s.params", name)
                    .isEqualTo(JSON.valueToTree(rule.get("params")));
        }
    }

    @Test
    @Transactional
    void 차량과_기사가_설계서의_규모대로_있다() {
        assertThat(count("vehicles")).isEqualTo(200);
        assertThat(count("drivers")).isEqualTo(200);
    }

    @Test
    @Transactional
    void 모든_차량이_fulfillment_의_캠프에_붙어_있다() {
        // 서비스 간 FK 는 불변규칙 3 이 금지한다. 값이 맞는지는 이 테스트가 대신 본다 —
        // 두 시드가 갈라지면 차량이 존재하지 않는 캠프에 매달린다.
        @SuppressWarnings("unchecked")
        List<String> campIds = entityManager
                .createNativeQuery("SELECT DISTINCT camp_id::text FROM vehicles")
                .getResultList();

        assertThat(campIds).hasSize(10)
                .allSatisfy(id -> assertThat(id).startsWith(CAMP_ID_PREFIX));
    }

    @Test
    @Transactional
    void 냉장과_위험물_차량이_둘_다_있고_전부는_아니다() {
        // 전부 냉장이면 cold-chain 하드 룰이 한 번도 걸리지 않고, 하나도 없으면 막다른 길이다.
        long cold = count("vehicles WHERE is_cold");
        long hazmat = count("vehicles WHERE allows_hazmat");

        assertThat(cold).isBetween(1L, 199L);
        assertThat(hazmat).isBetween(1L, 199L);
    }

    @Test
    @Transactional
    void 차종이_섞여_있다() {
        @SuppressWarnings("unchecked")
        List<String> types = entityManager
                .createNativeQuery("SELECT DISTINCT type FROM vehicles ORDER BY type")
                .getResultList();

        assertThat(Set.copyOf(types)).containsExactlyInAnyOrder("VAN", "TRUCK");
    }

    private long count(String from) {
        return ((Number) entityManager.createNativeQuery("SELECT count(*) FROM " + from)
                .getSingleResult()).longValue();
    }

    private static List<Map<String, Object>> contractRules() {
        try {
            return JSON.readValue(Files.readString(RULES_CONTRACT), new TypeReference<>() { });
        } catch (IOException e) {
            throw new UncheckedIOException("룰 계약 파일을 읽을 수 없습니다: " + RULES_CONTRACT, e);
        }
    }

    /**
     * <strong>어느 시각에 계획해도 실행 가능한 근무조가 있다</strong> (ADR-030).
     *
     * <p>2026-09-05 에는 아니었다. 200대 전부가 06:00–22:00 이라 근무 종료 한 시간 전부터 다음
     * 날 근무 시작까지 실행 가능한 라우트가 하나도 없었고, `make demo` 와 CI 스모크가 **하루
     * 8시간** 실패했다. 러너가 UTC 라 그 창은 13:00–21:00 UTC 였다.
     *
     * <p>이 테스트는 <strong>벽시계에 의존하지 않는다</strong> — 24시간을 한 시간씩 전부 돈다.
     * 데모를 특정 시각에 돌려 보는 것으로는 그 창이 닫혔다는 것을 증명할 수 없다(그 시각에
     * 통과했다는 것만 증명한다). 이 저장소가 여러 번 데인 형태다.
     */
    @Test
    @Transactional
    void 어느_시각에_계획해도_출발할_수_있는_차량이_있다() {
        UUID campId = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
        // 근무는 KST 벽시계다 (JdbcReferenceData 의 ZONE). 그 시간대의 하루를 돈다.
        LocalDate day = LocalDate.of(2026, 9, 8);
        List<String> deadHours = new ArrayList<>();

        for (int hour = 0; hour < 24; hour++) {
            Instant planFor = day.atTime(hour, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
            List<VehicleSpec> fleet = referenceData.availableAt(campId, planFor);
            // §6.3 은 복귀가 근무 종료 − 30분 버퍼 안이기를 요구한다. 출발은 근무 시작 이후로
            // 밀리므로(RouteState.empty), 실제로 쓸 수 있는 시간은 아래와 같다.
            boolean usable = fleet.stream().anyMatch(vehicle -> {
                Instant departAt = planFor.isBefore(vehicle.shift().start())
                        ? vehicle.shift().start() : planFor;
                // 두 조건이 함께 있어야 한다. **출발이 곧이어야 하고**(약속창은 계획 시각
                // 기준 몇 시간 안이다 — 내일 아침에 출발하는 차량은 TIME_WINDOW_LIMIT 에
                // 전부 걸린다), 출발 뒤 근무가 남아 있어야 한다(§6.3 복귀 − 30분 버퍼).
                // 앞의 조건을 빼면 이 테스트는 **공허해진다** — 어느 시각이든 "내일 근무" 가
                // 있으므로 언제나 통과한다. 실제로 처음 판이 그랬고, 짝 테스트
                // (시드에 자정을 넘는 근무조가 있다)가 그것을 잡았다.
                return Duration.between(planFor, departAt).toHours() <= 1
                        && Duration.between(departAt, vehicle.shift().end()).toMinutes() >= 60;
            });
            if (!usable) {
                deadHours.add("%02d:00 KST".formatted(hour));
            }
        }

        assertThat(deadHours)
                .as("이 시각들에는 실행 가능한 라우트가 없다 — 하루 8시간 실패가 돌아왔다는 뜻이다")
                .isEmpty();
    }

    /** 야간조가 실제로 자정을 넘는가. 위 테스트가 공허하지 않으려면 이것이 참이어야 한다. */
    @Test
    @Transactional
    void 시드에_자정을_넘는_근무조가_있다() {
        long night = ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM vehicles WHERE active AND shift_end <= shift_start")
                .getSingleResult()).longValue();

        assertThat(night)
                .as("야간조가 없으면 위 테스트는 주간조 하나로 통과할 수 없다 — 전제다")
                .isEqualTo(80L);
    }
}
