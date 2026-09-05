package com.dawnline.dispatch.domain.optimizer;

import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.GANGNAM;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CandidateTest {

    @Test
    void 권역은_좌표에서_나온다() {
        // 같은 사실을 두 필드로 들고 있으면 갈라진다 (ADR-021 — 권역은 geohash5 셀이다).
        Candidate candidate = OptimizerFixtures.candidate(GANGNAM);

        assertThat(candidate.zone()).isEqualTo(GANGNAM.geohash5()).hasSize(5);
    }

    @Test
    void 통합_키는_geohash7_과_약속창이다() {
        Candidate first = OptimizerFixtures.candidate(GANGNAM);
        Candidate second = OptimizerFixtures.candidate(GANGNAM);

        assertThat(first.mergeKey()).isEqualTo(second.mergeKey());
        assertThat(first.mergeKey().geohash7()).hasSize(7);
    }

    @Test
    void 약속창이_다르면_통합되지_않는다() {
        Candidate first = OptimizerFixtures.candidate(GANGNAM);
        Candidate second = new Candidate(first.id(), GANGNAM, first.parcel(),
                new com.dawnline.common.TimeWindow(OptimizerFixtures.START.plusSeconds(1),
                        OptimizerFixtures.START.plusSeconds(7200)),
                90, 0);

        assertThat(first.mergeKey()).isNotEqualTo(second.mergeKey());
    }
}
