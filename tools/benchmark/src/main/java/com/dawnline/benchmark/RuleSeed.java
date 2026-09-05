package com.dawnline.benchmark;

import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.rule.DispatchRules;
import com.dawnline.dispatch.domain.optimizer.rule.RuleDefinition;
import com.dawnline.dispatch.domain.optimizer.rule.RuleSeverity;
import com.dawnline.dispatch.domain.optimizer.rule.RuleType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code contracts/seed/dispatch-rules.json} → {@link RuleSet}.
 *
 * <p>벤치마크가 <strong>운영과 같은 룰</strong>로 돌아야 비교표가 뜻이 있다. 그래서 이 파일을
 * 읽는다 — 3-5a 의 {@code R__seed_dispatch.sql} 이 넣는 것과 같은 내용이다.
 *
 * <p>파싱이 여기 있는 이유: {@code domain.optimizer} 는 프레임워크 비의존이라 Jackson 도 들어갈
 * 수 없다(불변규칙 5). 도메인은 이미 파싱된 {@code Map} 을 받고, "문자열을 Map 으로 읽는 한 줄" 은
 * 출처가 다른 쪽(JSONB / 파일)이 각자 한다.
 */
public final class RuleSeed {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 저장소 루트 기준 시드 경로. */
    public static final Path DEFAULT_RELATIVE = Path.of("contracts", "seed", "dispatch-rules.json");

    private RuleSeed() {
    }

    /**
     * 시드 파일을 찾는다 — 현재 디렉터리부터 위로 올라가며 {@code contracts/seed} 를 찾는다.
     *
     * <p>{@code EventContracts} 가 계약 디렉터리를 찾는 방식과 같다. 모듈 디렉터리에서 돌리든
     * 저장소 루트에서 돌리든 같은 파일을 읽어야, "어디서 실행했느냐" 가 결과를 바꾸지 않는다.
     */
    public static Path locate() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path seed = candidate.resolve(DEFAULT_RELATIVE);
            if (Files.isRegularFile(seed)) {
                return seed;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "%s 를 찾지 못했습니다. --rules 로 직접 지정하세요.".formatted(DEFAULT_RELATIVE));
    }

    /**
     * 시드 파일을 읽어 룰 묶음을 만든다.
     *
     * @param file    시드 JSON 경로
     * @param version 룰 버전. 벤치마크는 시드를 그대로 쓰므로 1 이다
     */
    public static RuleSet load(Path file, int version) {
        List<Map<String, Object>> raw;
        try {
            raw = JSON.readValue(Files.readString(file), new TypeReference<>() { });
        } catch (IOException e) {
            throw new UncheckedIOException("룰 시드를 읽을 수 없습니다: " + file.toAbsolutePath(), e);
        }
        return DispatchRules.ruleSet(raw.stream().map(RuleSeed::toDefinition).toList(), version);
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
}
