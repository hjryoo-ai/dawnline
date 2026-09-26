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
# 부르는 쪽이 고른 compose 프로젝트(시뮬레이션 스택 — make sim-up, ADR-066 결정 6)를 .env 의 COMPOSE_PROJECT_NAME 이 덮지 않게.
caller_project="${COMPOSE_PROJECT_NAME:-}"
set -a; . deploy/compose/.env; set +a
COMPOSE_PROJECT_NAME="${caller_project:-$COMPOSE_PROJECT_NAME}"

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

# --- 카오스 넷이 함께 쓰는 것 ------------------------------------------------------------------------------

# 시나리오의 주문 수 — scenarios.yml 이 진실이다(검증 표 V1 의 전제 --expect-orders).
orders_of() { awk -v s="$1:" '$1 == s { f = 1 } f && $1 == "orders:" { print $2; exit }' tools/sim-runner/src/main/resources/scenarios.yml; }

# Prometheus 전제 — 규칙을 다시 읽히고, 적재 · 평가가 성공했는지 본다. 떠 있는 Prometheus 는 규칙 파일이 바뀌어도 스스로 다시 읽지
# 않는다 — chaos-db 2차 실행의 앞 10분이 옛 식으로 판정됐다(2026-09-25). 설정 파일이 컨테이너에서 사라져도(단일 파일 바인드 마운트 ·
# git checkout) 재적재가 실패한다 — make obs-check 1 이 같은 것을 본다(§13 「꺼 둔 검증」 규칙판).
prom_preflight() {
  curl -sf "http://localhost:$PROMETHEUS_PORT/-/ready" >/dev/null || { echo "Prometheus 가 없다" >&2; return 1; }
  curl -sf -X POST "http://localhost:$PROMETHEUS_PORT/-/reload" >/dev/null || { echo "Prometheus 규칙을 다시 읽히지 못했다" >&2; return 1; }
  local errors
  errors=$(curl -s "http://localhost:$PROMETHEUS_PORT/api/v1/rules" | jq '[.data.groups[].rules[] | select((.lastError // "") != "")] | length')
  [[ "$errors" == 0 ]] || { echo "적재된 규칙에 평가 오류가 있다($errors)" >&2; return 1; }
  [[ "$(curl -s "http://localhost:$PROMETHEUS_PORT/api/v1/status/runtimeinfo" | jq -r .data.reloadConfigSuccess)" == true ]] \
    || { echo "Prometheus 가 설정 · 규칙을 다시 읽는 데 실패했다 — dc up -d --force-recreate prometheus" >&2; return 1; }
}

# 가장 이른 OPEN 웨이브 — 같은 캠프 · 티어에 더 이른 OPEN 이 없는 것. 조기 마감이 그 캠프 · 티어의 밀림을 하나만 쓴다(ADR-063 —
# 넷을 앞선 실행들이 쓰면 다음 실행의 주문이 MAX_PUSHES_EXCEEDED 가 된다, 검증 표 V1·사유가 그것을 본다).
# 인자가 있으면 그 수 이상의 주문이 든 웨이브만 — 계획이 돌 것이 있어야 하는 카오스(chaos-kill)가 쓴다.
earliest_open_wave() {
  sqlv fulfillment "SELECT w.id FROM waves w WHERE w.status = 'OPEN' AND NOT EXISTS (
            SELECT 1 FROM waves e WHERE e.status = 'OPEN' AND e.camp_id = w.camp_id AND e.service_tier = w.service_tier
               AND e.cutoff_at < w.cutoff_at)
          AND (SELECT count(*) FROM fulfillment_orders o WHERE o.wave_id = w.id AND o.status = 'PLANNED') >= ${1:-0}
          ORDER BY w.cutoff_at, (SELECT count(*) FROM fulfillment_orders o WHERE o.wave_id = w.id) DESC LIMIT 1"
}

# 운영자 커맨드 하나 — ops-api 로 조기 마감을 보낸다. 「응답 감사」를 낸다. 감사 행이 무엇으로 남는지는 그대로 본다 — 코어의 5xx ·
# 타임아웃이 UNKNOWN 을 만들면 7-3b 의 해소 경로가 닫는다. 만들지 않는다(IMPLEMENTATION_PLAN 7-3, D3).
operator_close() {
  local wave=$1 reason=$2 body=$3 token code audit
  token=$(bash tools/ops-token/ops-token.sh OPS_OPERATOR chaos-operator)
  code=$(curl -s -o "$body" -w '%{http_code}' --max-time 90 -X POST \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -d "{\"reason\":\"$reason\"}" "http://localhost:$OPS_API_PORT/api/v1/waves/$wave/close")
  audit=$(sqlv ops "SELECT result FROM audit_logs WHERE action = 'CLOSE_WAVE' AND target_id = '$wave' ORDER BY created_at DESC LIMIT 1")
  echo "$code ${audit:-없음}"
}

# 판정 목록 — check <문장> <ok|그 밖>. 파이프 밖에서 부른다(서브셸에서 세운 fail 은 밖에 남지 않는다 — chaos-db 첫 실행).
fail=0; verdicts=""
check() { if [[ "$2" == ok ]]; then verdicts+="- ✅ $1"$'\n'; else verdicts+="- ✗ $1"$'\n'; fail=1; fi; }
observe() { verdicts+="- 관찰: $1"$'\n'; }
