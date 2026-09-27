package com.dawnline.dispatch.application.port.in;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code delivery.at-risk} 하나를 받아 부분 재계획을 돌린다 (DESIGN.md §6.8, ADR-048).
 *
 * <h2>페이로드는 트리거지 입력이 아니다</h2>
 * 명령이 이벤트의 필드를 거의 그대로 들고 있지만, <strong>재계획이 읽는 것은 dispatch 자기
 * DB</strong>다 — 남은 stop 은 {@code route_stops.status} 가, 편차는 {@code route_stops.actual_at}
 * 이 말한다. {@link ReplanCommand#deviationSeconds} 는 <em>대조값</em>이고, 자기 값과 갈리면
 * {@code dawnline_at_risk_deviation_mismatch_total} 이 오른다(ADR-048 결정 1·2).
 *
 * <p>{@code remainingStops} 를 명령에 싣지 않는 이유도 같다. 그 목록은 tracking 이 본 것이고,
 * dispatch 는 자기 테이블에 같은 것을 갖고 있다. 두 개를 받아 두면 언젠가 그쪽을 쓰게 된다.
 */
public interface ReplanRouteUseCase {

    /**
     * 게이트 없이 — 쓰기가 자기 트랜잭션을 연다.
     *
     * @param command 무엇이 위험하다고 알려 왔는가
     * @return 무엇을 했는가
     */
    default Outcome replan(ReplanCommand command) {
        return replan(command, WriteGate.OPEN).orElseThrow();
    }

    /**
     * 읽기 → 계산 → 쓰기 (ADR-068 결정 1). 계산은 트랜잭션 밖에서 돌고, 게이트는 쓰기만 감싼다.
     *
     * @param command 무엇이 위험하다고 알려 왔는가
     * @param gate    쓰기를 감싸는 게이트 — at-risk 리스너의 멱등 게이트. 계산 뒤에 한 번 불린다
     * @return 무엇을 했는가. 게이트가 쓰기를 건너뛰었으면(같은 이벤트를 이미 처리했다) 비어 있다 — 셀 것이 없다
     */
    Optional<Outcome> replan(ReplanCommand command, WriteGate gate);

    /**
     * 재계획 요청.
     *
     * @param routeId          위험한 라우트
     * @param campId           출발 캠프. 페이로드가 싣고 오지만 dispatch 는 자기
     *                         {@code route_plans} 에서도 안다 — 둘이 다르면 그것은 잘못된
     *                         라우팅이고 조용히 넘기지 않는다
     * @param detectedAt       tracking 이 위험을 판정한 시각
     * @param deviationSeconds tracking 이 본 편차(초). <strong>대조값</strong>이다
     */
    record ReplanCommand(UUID routeId, UUID campId, Instant detectedAt, long deviationSeconds) {

        public ReplanCommand {
            Objects.requireNonNull(routeId, "routeId");
            Objects.requireNonNull(campId, "campId");
            Objects.requireNonNull(detectedAt, "detectedAt");
        }

        /** tracking 이 본 편차. */
        public Duration deviation() {
            return Duration.ofSeconds(deviationSeconds);
        }
    }

    /**
     * 재계획 하나의 결과 (§9.1 {@code dawnline_replan_total} 의 {@code outcome} 라벨).
     *
     * <p>어느 갈래로 끝나도 <strong>소비는 성공한다</strong>. DLQ 로 보내면 고칠 수 없는 것이
     * 재시도되고, 사람이 열어도 할 일이 없다 (ADR-048 결정 5).
     */
    enum Outcome {

        /** 옮겼다. {@code plan_explanations} 에 설명이 남는다. */
        APPLIED,

        /** 쿨다운 안이다 — 이 라우트는 방금 재계획했다. */
        COOLDOWN,

        /**
         * 닿은 stop 이 없어 편차를 <strong>모른다</strong>.
         *
         * <p>{@link #NO_GAIN} 과 따로 있는 이유는 모름이 0 이 아니기 때문이다. 편차를 0 으로
         * 두고 돌리면 출발 지연 라우트가 「옮겨도 이득 없음」으로 조용히 닫힌다.
         */
        NO_ANCHOR,

        /** 옮길 stop 이 없거나 받을 라우트가 없다. */
        NO_CANDIDATE,

        /** 옮겨도 <strong>두 라우트 총비용</strong>이 줄지 않는다 (§6.1 목적함수 그대로). */
        NO_GAIN,

        /**
         * 평가 상한에 걸려 이동을 하나도 찾지 못했다 — <strong>다 못 봤다</strong> (§6.8, 7-0 D4).
         *
         * <p>{@link #NO_GAIN} 과 따로 있는 이유는 {@link #NO_ANCHOR} 와 같다: 「다 봤는데 없다」와 「다 못 봤다」는 다른 말이고,
         * 접으면 상한이 걸리는 규모(peak)에서 재계획이 무엇을 못 했는지가 사라진다. 이동을 찾았는데 걸린 것은 {@link #APPLIED}
         * 이고 설명 행이 {@code searchTruncated} 를 싣는다.
         */
        TRUNCATED,

        /**
         * 계산하는 동안 전제가 바뀌어 <strong>결과를 버렸다</strong> (ADR-068 결정 2) — 옮길 stop 이 {@code PLANNED} 가 아니게
         * 됐거나(기사가 닿았다 · 끝냈다), 주문이 원 라우트를 떠났거나 취소됐거나, 원 · 대상 라우트의 {@code revision} 이 올랐다.
         *
         * <p>쿨다운을 집지 않는다 — 재계획을 한 것이 아니라 계산의 전제가 바뀐 것이고, 다음 at-risk 가 새 사실로 다시 푼다.
         * {@link #NO_GAIN} 과 따로 있는 이유는 {@link #TRUNCATED} 와 같다: 「옮겨도 이득 없음」과 「옮길 전제가 무너졌다」는 다른 말이다.
         */
        STALE;

        /** 메트릭 라벨 값 — {@code NO_ANCHOR} → {@code no-anchor}. */
        public String label() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }
}
