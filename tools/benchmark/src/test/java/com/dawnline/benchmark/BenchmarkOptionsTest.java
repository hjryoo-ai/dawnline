package com.dawnline.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class BenchmarkOptionsTest {

    private static final List<String> REGISTERED = List.of("unassign-all");

    @Test
    void 인자가_없으면_기본값으로_돈다() {
        BenchmarkOptions options = BenchmarkOptions.parse(new String[0], REGISTERED);

        assertThat(options.dataset()).isEqualTo(Dataset.SMALL);
        assertThat(options.strategies()).isEqualTo(REGISTERED);
        assertThat(options.repeats()).isEqualTo(5);
        assertThat(options.budget()).isEqualTo(Duration.ofSeconds(30));
        assertThat(options.out()).isNull();
    }

    @Test
    void CLAUDE_md_에_적힌_명령이_그대로_읽힌다() {
        BenchmarkOptions options = BenchmarkOptions.parse(
                new String[] {"--dataset", "small", "--strategies", "baseline-nn,sweep-greedy-nn+ls"},
                REGISTERED);

        assertThat(options.dataset()).isEqualTo(Dataset.SMALL);
        assertThat(options.strategies()).containsExactly("baseline-nn", "sweep-greedy-nn+ls");
    }

    @Test
    void 모든_옵션을_읽는다() {
        BenchmarkOptions options = BenchmarkOptions.parse(new String[] {
                "--dataset", "large", "--strategies", "a", "--repeats", "3",
                "--seed", "77", "--budget-seconds", "12",
                "--rules", "/tmp/r.json", "--out", "/tmp/o.md"}, REGISTERED);

        assertThat(options.dataset()).isEqualTo(Dataset.LARGE);
        assertThat(options.repeats()).isEqualTo(3);
        assertThat(options.seed()).isEqualTo(77L);
        assertThat(options.budget()).isEqualTo(Duration.ofSeconds(12));
        assertThat(options.rulesFile()).isEqualTo(Path.of("/tmp/r.json"));
        assertThat(options.out()).isEqualTo(Path.of("/tmp/o.md"));
    }

    @Test
    void 오타_난_옵션은_조용히_무시되지_않는다() {
        // 무시되면 "기본값으로 돌았다" 는 사실이 리포트 어디에도 남지 않는다.
        assertThatThrownBy(() -> BenchmarkOptions.parse(new String[] {"--datset", "small"}, REGISTERED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--datset");
    }

    @Test
    void 값이_빠진_옵션은_사용법과_함께_실패한다() {
        assertThatThrownBy(() -> BenchmarkOptions.parse(new String[] {"--dataset"}, REGISTERED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--dataset <small|medium|large|peak>");
    }

    @Test
    void 알_수_없는_데이터셋은_가능한_값을_알려_준다() {
        assertThatThrownBy(() -> BenchmarkOptions.parse(new String[] {"--dataset", "huge"}, REGISTERED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("peak");
    }

    @Test
    void 반복이_0_이면_거부한다() {
        assertThatThrownBy(() ->
                BenchmarkOptions.parse(new String[] {"--repeats", "0"}, REGISTERED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
