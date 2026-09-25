#!/usr/bin/env bash
# =============================================================================
# 카오스 공통 준비 (DESIGN.md §13 「카오스」, docs/runbooks/README.md 「공통 준비」)
#
# dc · prom · logs · sql 넷은 런북의 공통 준비와 같은 함수다 — 카오스가 런북의 절차를 처음 밟는 자리이고(7-5),
# 같은 명령으로 본 것이어야 런북의 문장이 검증된다. 나머지는 스크립트가 기다리고 판정하는 데 쓰는 것뿐이다.
#
# 이 파일은 source 한다. 저장소 루트를 찾아 그곳에서 돈다.
# =============================================================================

CHAOS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$CHAOS_ROOT" || exit 1
set -a; . deploy/compose/.env; set +a

dc()   { docker compose -f deploy/compose/docker-compose.yml --env-file deploy/compose/.env --profile app --profile obs "$@"; }
prom() { curl -s "http://localhost:$PROMETHEUS_PORT/api/v1/query" --data-urlencode "query=$1" | jq -c '.data.result[] | [.metric, .value[1]]'; }
logs() { dc logs --no-log-prefix --since "${2:-30m}" "$1"; }
sql()  { dc exec -T -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" postgres psql -U "$POSTGRES_SUPERUSER" -d "dawnline_$1" -c "$2"; }

# 값 하나만 — 머리 · 꼬리 없이.
sqlv() { dc exec -T -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" postgres psql -U "$POSTGRES_SUPERUSER" -d "dawnline_$1" -Atq -c "$2"; }

# PromQL 한 값 — 결과가 없으면 빈 문자열(0 으로 접지 않는다 — 없는 시계열은 0 이 아니다, §9.1).
promv() { curl -s "http://localhost:$PROMETHEUS_PORT/api/v1/query" --data-urlencode "query=$1" | jq -r '.data.result[0].value[1] // empty'; }

# 알림의 지금 상태 — firing · pending · 빈 문자열(비활성). 규칙 파일을 실제로 적재한 Prometheus 에 묻는다(promtool 이 아니다).
alert_state() {
  curl -s "http://localhost:$PROMETHEUS_PORT/api/v1/alerts" \
    | jq -r --arg a "$1" '[.data.alerts[] | select(.labels.alertname == $a) | .state] | if length == 0 then "" elif index("firing") then "firing" else "pending" end'
}

ts()  { date -u +%H:%M:%S; }
say() { echo "=== $(ts) $*"; }

# 조건이 참이 될 때까지 기다린다 — wait_until <초> <설명> <명령…>. 시간 안에 안 되면 1.
wait_until() {
  local limit=$1 what=$2; shift 2
  local start=$SECONDS
  until "$@"; do
    if (( SECONDS - start >= limit )); then
      say "시간 초과(${limit}s): $what"
      return 1
    fi
    sleep 5
  done
  say "됐다($((SECONDS - start))s 안): $what"
}
