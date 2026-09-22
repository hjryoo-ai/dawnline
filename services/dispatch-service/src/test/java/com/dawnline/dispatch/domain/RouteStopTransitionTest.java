package com.dawnline.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.dispatch.domain.RouteStopTransition.Verdict;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 축 규칙의 <strong>네 번째 자리</strong> (ADR-047 결정 4).
 *
 * <p>불변규칙 6 을 ArchUnit 이 지키지 못하는 자리라(애그리거트가 없다) 이 테스트가 그 몫을
 * 대신한다 — §13 매핑표에 그렇게 적혀 있다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("RouteStopTransition — delivery.status 가 route_stops.status 에 하는 일")
class RouteStopTransitionTest {

    /** {@code delivery.status} 가 나를 수 있는 상태 — 계약의 세 값. */
    private static Set<RouteStopStatus> reportable() {
        Set<RouteStopStatus> values = EnumSet.allOf(RouteStopStatus.class);
        // 드는 방식이 아니라 빼는 방식이다 — 상태가 하나 늘면 그 값이 자동으로 여기 들어오고,
        // «나를 수 있는가» 는 visited() 하나가 답한다.
        values.removeIf(status -> !status.visited());
        return values;
    }

    @Test
    void 앞으로_가는_전이는_전부_적용한다() {
        assertThat(RouteStopTransition.decide(RouteStopStatus.PLANNED, RouteStopStatus.ARRIVED))
                .isEqualTo(Verdict.APPLY);
        assertThat(RouteStopTransition.decide(RouteStopStatus.ARRIVED, RouteStopStatus.COMPLETED))
                .isEqualTo(Verdict.APPLY);
    }

    @Test
    void 건너뜀은_예외가_아니라_정식_전이다() {
        // 배송이 끝났다면 배송은 시작된 것이다. 도착 스캔이 아직 안 온 것은 우리가 알게 된
        // 순서일 뿐이다 (§5.1, ADR-017).
        assertThat(RouteStopTransition.decide(RouteStopStatus.PLANNED, RouteStopStatus.COMPLETED))
                .isEqualTo(Verdict.APPLY);
        assertThat(RouteStopTransition.decide(RouteStopStatus.PLANNED, RouteStopStatus.FAILED))
                .isEqualTo(Verdict.APPLY);
    }

    @Test
    void 역행과_제자리는_철_지난_이벤트다() {
        assertThat(RouteStopTransition.decide(RouteStopStatus.COMPLETED, RouteStopStatus.ARRIVED))
                .isEqualTo(Verdict.STALE);
        assertThat(RouteStopTransition.decide(RouteStopStatus.ARRIVED, RouteStopStatus.ARRIVED))
                .isEqualTo(Verdict.STALE);
    }

    @Test
    void 실패는_종결이라_완료가_뒤따라와도_옮기지_않는다() {
        // FAILED 와 COMPLETED 는 같은 단계다. 둘 사이에 순서가 없으므로 먼저 도착한 것이 남는다 —
        // 그리고 그것이 §6.8 이 「다시 배정하지 않는다」고 읽는 값이다.
        assertThat(RouteStopTransition.decide(RouteStopStatus.FAILED, RouteStopStatus.COMPLETED))
                .isEqualTo(Verdict.STALE);
        assertThat(RouteStopTransition.decide(RouteStopStatus.COMPLETED, RouteStopStatus.FAILED))
                .isEqualTo(Verdict.STALE);
    }

    @ParameterizedTest
    @EnumSource(value = RouteStopStatus.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"PLANNED", "CANCELLED"})
    void 취소된_stop_은_무엇이_와도_옮기지_않는다(RouteStopStatus reported) {
        // dispatch 에서 CANCELLED 는 「계획에서 뺐다」는 뜻이다. §6.10 이 이미 시각을 재전파하고
        // 새 개정을 발행한 자리라, 여기에 COMPLETED 를 적으면 나간 개정과 저장된 계획이 어긋난다.
        assertThat(RouteStopTransition.decide(RouteStopStatus.CANCELLED, reported))
                .isEqualTo(Verdict.AFTER_CANCEL);
    }

    @ParameterizedTest
    @EnumSource(value = RouteStopStatus.class, mode = EnumSource.Mode.INCLUDE,
            names = {"PLANNED", "CANCELLED"})
    void 스캔이_나를_수_없는_상태는_계약_위반이라_소리를_낸다(RouteStopStatus reported) {
        // 조용히 무시하면 「발행자가 계약을 어겼다」가 사라진다. 모르는 *문자열* 은 리스너가
        // 이미 걸렀으므로(§4.7), 여기 오는 것은 아는 값을 잘못 쓴 경우뿐이다.
        assertThatThrownBy(() -> RouteStopTransition.decide(RouteStopStatus.PLANNED, reported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(reported.name());
    }

    /**
     * 표의 모든 칸이 <strong>정확히 하나</strong>의 판정을 갖는다 — 그리고 세 판정이 전부 쓰인다.
     *
     * <p>조합을 손으로 나열하지 않는다. 상태가 하나 늘면 이 검사의 칸이 저절로 늘어나고, 그
     * 상태를 아무도 생각하지 않았으면 여기서 빨개진다.
     */
    @Test
    void 도착상태_x_현재상태_의_모든_칸에_판정이_있다() {
        List<Verdict> verdicts = new ArrayList<>();
        for (RouteStopStatus current : RouteStopStatus.values()) {
            for (RouteStopStatus reported : reportable()) {
                verdicts.add(RouteStopTransition.decide(current, reported));
            }
        }

        assertThat(verdicts)
                .as("칸 수 = 현재 상태 %d × 보고 가능 상태 %d",
                        RouteStopStatus.values().length, reportable().size())
                .hasSize(RouteStopStatus.values().length * reportable().size());
        assertThat(verdicts)
                .as("세 판정이 전부 쓰이지 않으면 규칙 하나가 죽은 코드라는 뜻이다")
                .containsAll(EnumSet.allOf(Verdict.class));
    }
}
