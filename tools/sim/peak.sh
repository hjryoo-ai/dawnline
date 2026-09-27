#!/usr/bin/env bash
# =============================================================================
# make peak [PEAK=peak-day] — 창 시나리오를 시뮬레이션 스택에 돌린다 (DESIGN.md 부록 A · §5.6 「성수기 증차」, ADR-066 · ADR-067)
#
# sim-runner 는 호스트에서 돈다(bootRun) — 서비스와 같은 오프셋(build/sim-offset, make sim-up 이 적는다)과 프로필 sim 으로.
# 그래야 sim-runner 의 주입 시계가 다섯 서비스와 같은 유효 시각을 말하고, 창(22:58)까지 기다림 · 증차 완료 시각 · closed_at 비교가
# 한 축에 선다. 함대 단계는 ops-api 를 운영자의 토큰으로 부른다 — 토큰은 여기서 찍고(12시간, 벽시계 — ADR-066 결정 5) 저장하지 않는다.
#
#   make sim-up                 # 유효 시각 22:40 KST 에서 기동
#   make peak                   # peak-day — 22:58 까지 기다리고 한 시간 보낸 뒤 증차 · 계획 · 기사 · 정리
#   make peak PEAK=overload-day # 같은 물량, 증차 없음
#
# 종료 코드는 sim-runner 의 것이다 — 주문 · 기사 · 함대 단계(전제 · 템플릿 없음 · 시간 예산 · 계획 실패 · 비활성화) 중 하나라도
# 어긋나면 0 이 아니다. 미배정의 §6.7 판정은 리포트 머리에만 있다(ADR-067 결정 8).
# =============================================================================
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ENV_FILE="deploy/compose/.env"
set -a; . "$ENV_FILE"; set +a

PEAK="${PEAK:-peak-day}"
OFFSET_FILE="build/sim-offset"

if ! docker ps --filter "label=com.docker.compose.project=dawnline-sim" --format '{{.Names}}' | grep -q .; then
  echo "시뮬레이션 스택(프로젝트 dawnline-sim)이 떠 있지 않다 — make sim-up 먼저." >&2
  exit 2
fi
if [[ ! -s "$OFFSET_FILE" ]]; then
  echo "$OFFSET_FILE 이 없다 — make sim-up 이 기동하며 적는다. 스택을 이 스크립트 밖에서 올렸다면 오프셋을 모른다." >&2
  exit 2
fi
OFFSET="$(cat "$OFFSET_FILE")"

DAWNLINE_SIM_OPS_TOKEN="$(bash tools/ops-token/ops-token.sh OPS_OPERATOR sim-peak)"
export DAWNLINE_SIM_OPS_TOKEN

# 호스트에서 compose 스택을 부른다 — 주소의 진실은 .env 의 게시 포트다. sim-runner 의 기본값은 각 서비스의 bootRun 포트라서
# ops-api 는 8085(bootRun)와 8080(compose)이 갈린다 — 첫 make peak 가 그 자리에서 ConnectException 으로 멈췄다(2026-09-27,
# 전제 검사가 창을 기다리기 전이라 1초 만에). 넷을 다 여기서 정한다: 하나만 고치면 다음 어긋남이 같은 모양으로 온다.
export DAWNLINE_SIM_BASE_URL="http://localhost:${ORDER_SERVICE_PORT}"
export DAWNLINE_SIM_OPS_BASE_URL="http://localhost:${OPS_API_PORT}"
export DAWNLINE_SIM_SCAN_BASE_URL="http://localhost:${TRACKING_SERVICE_PORT}"
export SPRING_KAFKA_BOOTSTRAP_SERVERS="localhost:${KAFKA_EXTERNAL_PORT}"

echo "=== $PEAK — 오프셋 $OFFSET (make sim-up 의 값), 운영자 sim-peak"
exec ./gradlew --console=plain :tools:sim-runner:bootRun \
  --args="--dawnline.sim.scenario=$PEAK --spring.profiles.active=sim --dawnline.clock.offset=$OFFSET"
