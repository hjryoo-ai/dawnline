#!/usr/bin/env bash
# =============================================================================
# make sim-up · sim-down — 시뮬레이션 스택 (DESIGN.md §5.6 「시뮬레이션 시계」, ADR-066)
#
# 시뮬레이션은 스케줄이 아니라 시계를 옮긴다. 이 스크립트가 하는 일은 셋이다:
#   1. 자기 compose 프로젝트(dawnline-sim, 볼륨 dawnline-sim_*)에서 돈다 — 오프셋을 켜고 쓴 사실은 벽시계보다 미래라, 같은 볼륨으로
#      오프셋 0 에 돌아오면 시간이 뒤로 간다(결정 6). 개발 볼륨(dawnline_*)은 오프셋을 한 번도 보지 않는다.
#   2. 오프셋을 계산한다 — 기동하는 순간의 유효 시각이 SIM_AT(KST, 기본 22:40)이 되게. 같은 프로젝트에서 연달아 돌면
#      이미 적힌 가장 늦은 주문 시각보다 뒤의 SIM_AT 을 고른다 — 다음 실행이 앞 실행의 컷오프를 다시 만나지 않는다.
#   3. 다섯 서비스에 같은 값을 준다 — compose 앵커의 DAWNLINE_CLOCK_OFFSET 하나 + 프로필 sim(없으면 서비스가 기동을 거부한다).
#
#   make sim-up [SIM_AT=22:40]     개발 스택이 떠 있으면 거부한다(컨테이너 이름이 고정 — make down 먼저, 볼륨은 그대로다)
#   make sim-down                  컨테이너만 내린다(볼륨 유지 — 지우는 명령은 이 스크립트에 없다)
#
# 이 셸에서 다른 make 타깃을 시뮬레이션 스택에 쓰려면 COMPOSE_PROJECT_NAME=dawnline-sim 을 내보낸다(obs-check · chaos · smoke).
# =============================================================================
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ENV_FILE="deploy/compose/.env"
set -a; . "$ENV_FILE"; set +a

export COMPOSE_PROJECT_NAME=dawnline-sim
SIM_AT="${SIM_AT:-22:40}"
COMPOSE=(docker compose -f deploy/compose/docker-compose.yml --env-file "$ENV_FILE" --profile app --profile obs)

say() { echo "=== $(date -u +%H:%M:%S) $*"; }

# 개발 스택(프로젝트 dawnline)의 컨테이너가 하나라도 떠 있으면 — 이름이 같아 함께 뜨지 않는다.
dev_stack_running() {
  docker ps --filter "label=com.docker.compose.project=dawnline" --format '{{.Names}}' | grep -q .
}

# 이 프로젝트에 이미 적힌 가장 늦은 주문 시각(epoch 초). 볼륨이 새것이면(표가 없다) 0.
latest_fact() {
  "${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" postgres \
    psql -U "$POSTGRES_SUPERUSER" -d dawnline_order -Atq \
    -c "SELECT COALESCE(EXTRACT(EPOCH FROM max(placed_at))::bigint, 0) FROM orders" 2>/dev/null || echo 0
}

# 기동 순간의 유효 시각이 SIM_AT(KST)이 되는 오프셋(초) — max(지금, 가장 늦은 사실) 뒤의 첫 SIM_AT.
offset_seconds() {
  python3 - "$SIM_AT" "$1" <<'PY'
import sys, datetime as dt
at, latest = sys.argv[1], int(sys.argv[2])
kst = dt.timezone(dt.timedelta(hours=9))
now = dt.datetime.now(dt.timezone.utc)
base = max(now, dt.datetime.fromtimestamp(latest, dt.timezone.utc))
hh, mm = map(int, at.split(":"))
target = base.astimezone(kst).replace(hour=hh, minute=mm, second=0, microsecond=0)
while target <= base:
    target += dt.timedelta(days=1)
print(int((target - now).total_seconds()))
PY
}

case "${1:-}" in
  up)
    if dev_stack_running; then
      echo "개발 스택(프로젝트 dawnline)이 떠 있다 — 컨테이너 이름이 같아 함께 뜨지 않는다. make down 먼저(볼륨은 그대로다)." >&2
      exit 2
    fi
    say "시뮬레이션 프로젝트 $COMPOSE_PROJECT_NAME — 인프라부터(가장 늦은 사실을 읽으려고)"
    "${COMPOSE[@]}" up -d --wait postgres
    latest="$(latest_fact)"
    offset="$(offset_seconds "$latest")"
    export DAWNLINE_CLOCK_OFFSET="PT${offset}S" DAWNLINE_SPRING_PROFILES="compose,sim"
    say "오프셋 $DAWNLINE_CLOCK_OFFSET — 기동 순간의 유효 시각 $SIM_AT KST(가장 늦은 주문 $(python3 -c 'import sys, datetime as d; t = int(sys.argv[1]); print(d.datetime.fromtimestamp(t, d.timezone.utc).isoformat() if t else "없음")' "$latest"))"
    "${COMPOSE[@]}" up -d
    make --no-print-directory wait
    mkdir -p build && echo "$DAWNLINE_CLOCK_OFFSET" > build/sim-offset
    say "떴다. 서비스 로그의 「시계: 유효 시각」 · make obs-check 6 으로 다섯이 같은 오프셋인지 본다(COMPOSE_PROJECT_NAME=$COMPOSE_PROJECT_NAME)."
    ;;
  down)
    "${COMPOSE[@]}" down --remove-orphans
    ;;
  *)
    echo "사용법: $0 up|down" >&2
    exit 2
    ;;
esac
