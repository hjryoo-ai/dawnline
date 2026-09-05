package com.dawnline.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.dispatch.domain.optimizer.RuleSet;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 벤치마크가 <strong>운영과 같은 룰</strong>로 도는지. 다르면 비교표가 뜻이 없다. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RuleSeedTest {

    @Test
    void 전제_저장소_어디서_돌려도_같은_시드를_찾는다() {
        Path located = RuleSeed.locate();

        assertThat(Files.isRegularFile(located)).isTrue();
        assertThat(located).endsWith(RuleSeed.DEFAULT_RELATIVE);
    }

    @Test
    void 시드가_그대로_룰_묶음이_된다() {
        RuleSet rules = RuleSeed.load(RuleSeed.locate(), 1);

        assertThat(rules.hardRules()).isNotEmpty();
        assertThat(rules.softRules()).isNotEmpty();
        assertThat(rules.unassignedRules())
                .as("미배정 페널티가 없으면 '아무것도 안 하는 계획' 이 언제나 최적이다").isNotEmpty();
        assertThat(rules.version()).isEqualTo(1);
    }
}
