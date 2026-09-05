package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

    private static final Path RULES_CONTRACT = Path.of("../../contracts/seed/dispatch-rules.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** fulfillment 의 {@code R__seed_fulfillment.sql} 이 쓰는 캠프 UUID 접두사. */
    private static final String CAMP_ID_PREFIX = "01a06edd-6c00-7000-8001-";

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
}
