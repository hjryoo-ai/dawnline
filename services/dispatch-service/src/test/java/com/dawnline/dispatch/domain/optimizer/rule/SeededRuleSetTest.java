package com.dawnline.dispatch.domain.optimizer.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.dispatch.domain.optimizer.RuleSet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

/**
 * {@code contracts/seed/dispatch-rules.json} 이 실제로 룰 묶음이 되는지.
 *
 * <h2>이 파일이 두 곳에서 쓰인다</h2>
 * 지금은 이 테스트의 픽스처이고, Phase 3-5a 에서 {@code R__seed_dispatch.sql} 이 같은 내용을
 * {@code dispatch_rules} 에 넣는다. 두 벌을 두면 갈라지고, 갈라진 날 "테스트는 통과하는데 운영
 * 룰이 다르다" 가 된다. 5a 에서 SQL 과 이 파일을 대조하는 IT 가 붙는다
 * ({@code contracts/seed/order-service-geohash5.txt} 와 같은 방식).
 *
 * <p>JSON 파싱이 도메인이 아니라 여기 있는 이유: {@code domain.optimizer} 는 프레임워크
 * 비의존이다(불변규칙 5). 정의는 <em>이미 파싱된</em> {@code Map} 으로 들어가고, 파싱은 어댑터와
 * 벤치마크 하네스가 각자 한 줄로 한다 — 출처가 JSONB 와 파일로 다르기 때문이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SeededRuleSetTest {

    private static final Path SEED = Path.of("../../contracts/seed/dispatch-rules.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<RuleDefinition> seededDefinitions() {
        List<Map<String, Object>> raw;
        try {
            raw = JSON.readValue(Files.readString(SEED), new TypeReference<>() { });
        } catch (IOException e) {
            throw new UncheckedIOException("시드 룰 파일을 읽을 수 없습니다: " + SEED.toAbsolutePath(), e);
        }
        return raw.stream().map(SeededRuleSetTest::toDefinition).toList();
    }

    @SuppressWarnings("unchecked")
    private static RuleDefinition toDefinition(Map<String, Object> node) {
        return new RuleDefinition(
                (String) node.get("name"),
                RuleType.valueOf((String) node.get("type")),
                RuleSeverity.valueOf((String) node.get("severity")),
                ((Number) node.get("priority")).intValue(),
                (Map<String, Object>) node.get("params"));
    }

    @Test
    void 전제_시드_파일이_존재한다() {
        assertThat(SEED).as("경로가 어긋나면 아래 테스트가 파일 없이 통과할 수 있다").exists();
    }

    @Test
    void 시드가_그대로_룰_묶음이_된다() {
        RuleSet rules = DispatchRules.ruleSet(seededDefinitions(), 1);

        assertThat(rules.hardRules()).hasSize(6);
        assertThat(rules.softRules()).hasSize(4);
        assertThat(rules.unassignedRules()).hasSize(1);
    }

    @Test
    void 시드가_카탈로그_열_종을_모두_덮는다() {
        // §6.3 이 "초기 구현 범위" 로 열 종을 적었다. 하나가 빠지면 그 평가기는 한 번도 안 돈다.
        Set<RuleType> seeded = seededDefinitions().stream()
                .map(RuleDefinition::type).collect(java.util.stream.Collectors.toSet());

        assertThat(seeded).containsExactlyInAnyOrder(RuleType.values());
    }

    @Test
    void 하드가_소프트보다_먼저_평가되도록_우선순위가_잡혀_있다() {
        // 하드와 소프트는 각자 정렬되므로 이 검사는 "정의가 의도대로 적혀 있나" 를 본다.
        int lastHard = seededDefinitions().stream()
                .filter(definition -> definition.severity() == RuleSeverity.HARD)
                .mapToInt(RuleDefinition::priority).max().orElseThrow();
        int firstSoft = seededDefinitions().stream()
                .filter(definition -> definition.severity() == RuleSeverity.SOFT)
                .mapToInt(RuleDefinition::priority).min().orElseThrow();

        assertThat(lastHard).isLessThan(firstSoft);
    }

    @Test
    void 미배정_비용이_가장_뒤에_있다() {
        // 목적함수의 마지막 항이고, 다른 소프트 룰과 섞이면 읽는 사람이 순서를 오해한다.
        RuleDefinition unassigned = seededDefinitions().stream()
                .filter(definition -> definition.type() == RuleType.UNASSIGNED_PENALTY)
                .findFirst().orElseThrow();
        int maxOther = seededDefinitions().stream()
                .filter(definition -> definition.type() != RuleType.UNASSIGNED_PENALTY)
                .mapToInt(RuleDefinition::priority).max().orElseThrow();

        assertThat(unassigned.priority()).isGreaterThan(maxOther);
    }
}
