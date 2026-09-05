package com.dawnline.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SourceVersionTest {

    @Test
    void detect_저장소_안에서는_커밋을_읽는다() {
        // 전제: 이 테스트는 git 작업 트리 안에서 돈다. 아니면 아래 어설션은 아무것도 검사하지 않는다.
        SourceVersion detected = SourceVersion.detect();

        assertThat(detected.commit())
                .as("git 작업 트리 안에서 돌지 않으면 이 테스트는 전제가 무너진 것이다")
                .isNotNull()
                .matches("[0-9a-f]{7,40}");
    }

    @Test
    void describe_깨끗한_트리는_커밋만_적는다() {
        assertThat(new SourceVersion("abc1234", true).describe()).isEqualTo("커밋 `abc1234`");
    }

    @Test
    void describe_더러운_트리는_재현_불가를_함께_적는다() {
        assertThat(new SourceVersion("abc1234", false).describe())
                .contains("abc1234")
                .contains("커밋되지 않은 수정")
                .contains("재현할 수 없다");
    }

    @Test
    void describe_커밋을_모르면_부재를_값으로_적는다() {
        assertThat(SourceVersion.UNKNOWN.describe()).isEqualTo("커밋 `unknown`(git 없음)");
    }
}
