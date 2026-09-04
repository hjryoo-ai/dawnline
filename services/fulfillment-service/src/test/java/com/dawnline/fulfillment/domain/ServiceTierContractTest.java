package com.dawnline.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ServiceTier} 는 order-service 의 같은 이름 enum 과 <strong>의도적인 중복</strong>이다
 * (서비스 간 소스 의존 금지 — 불변규칙 3).
 *
 * <p>중복이므로 어긋날 수 있고, 어긋나면 {@code order.placed} 의 {@code serviceTier} 를
 * 역직렬화할 때 터진다. 공유되는 진실은 <strong>이벤트 계약</strong>이므로 여기서 그것과 같은지
 * 검사한다 — 어느 한쪽만 고치면 이 자리에서 깨진다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ServiceTierContractTest {

    private static final Path SCHEMA = Path.of("../../contracts/events/order.placed.v1.schema.json");

    @Test
    void enum_값이_이벤트_계약의_serviceTier_와_같다() {
        assertThat(Arrays.stream(ServiceTier.values()).map(Enum::name).toList())
                .as("contracts/events/order.placed.v1.schema.json 의 $defs.serviceTier.enum 과 다릅니다")
                .containsExactlyInAnyOrderElementsOf(contractTiers());
    }

    private static List<String> contractTiers() {
        try {
            JsonNode schema = JsonMapper.builder().build()
                    .readTree(Files.readString(SCHEMA, StandardCharsets.UTF_8));
            JsonNode values = schema.path("$defs").path("serviceTier").path("enum");
            assertThat(values.isArray()).as("$defs.serviceTier.enum 을 찾지 못했습니다").isTrue();
            List<String> tiers = new ArrayList<>();
            values.forEach(node -> tiers.add(node.stringValue()));
            return tiers;
        } catch (IOException e) {
            throw new UncheckedIOException("계약 파일을 읽을 수 없습니다: " + SCHEMA.toAbsolutePath(), e);
        }
    }
}
