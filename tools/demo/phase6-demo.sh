#!/usr/bin/env bash
# =============================================================================
# make demo 의 셋째 — Phase 6 데모 (IMPLEMENTATION_PLAN Phase 6 DoD 첫 줄, 묶음 C3)
#
# 보이려는 것 한 줄: **운영자가 웨이브를 조기 마감하고, 계획 결과와 라우트 지도를 보며, 특정 stop 을 다른
# 라우트로 옮긴다** — 화면(ops-web)이 부르는 그 경로로. 브라우저는 없다(사용자 결정): 화면이 부르는 요청을
# ops-web 의 nginx 에 그대로 보낸다. 그래서 이 스크립트는 화면의 계약과 프록시를 함께 본다 —
# 요청의 Authorization 과 응답의 X-Dawnline-Audit-Id 가 nginx 를 그대로 지나는지(ADR-057 결정 2).
#
# 앞의 두 데모와 달리 **시계를 당기지 않는다.** 마감은 운영자가 한다 — 그것이 이 Phase 의 기능이다.
#
# 확인은 세 곳에서 한다: 화면이 읽는 응답(ops-api 조회), 진실(dispatch DB), 감사(ops DB 의 audit_logs).
# 하나만 보면 「응답은 200 인데 적용되지 않았다」와 「적용됐는데 읽기 모델이 모른다」가 구별되지 않는다.
# =============================================================================
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

ENV_FILE="deploy/compose/.env"
COMPOSE=(docker compose -f deploy/compose/docker-compose.yml --env-file "$ENV_FILE")
set -a; . "$ENV_FILE"; set +a

DEMO_TIMEOUT="${DEMO_TIMEOUT:-120}"
WEB="http://localhost:${OPS_WEB_PORT}"
AUDIT_HEADER="X-Dawnline-Audit-Id"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
step() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }
fail() { printf '\n\033[31m실패: %s\033[0m\n\n' "$*" >&2; exit 1; }

psql_value() {
  "${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" postgres \
    psql -qtAX -U "$POSTGRES_SUPERUSER" -d "$1" -c "$2" | tr -d '[:space:]'
}
fq() { psql_value dawnline_fulfillment "$1"; }
dq() { psql_value dawnline_dispatch "$1"; }
aq() { psql_value dawnline_ops "$1"; }

# 화면이 보내는 요청 하나 — ops-web(nginx)을 지난다. 본문은 $WORK/body, 응답 헤더는 $WORK/headers, 상태는 stdout.
web() {
  local method="$1" path="$2" token="$3" body="${4:-}"
  local args=(-s -o "$WORK/body" -D "$WORK/headers" -w '%{http_code}' -X "$method" --max-time 30)
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' --data "$body")
  curl "${args[@]}" "$WEB$path" || echo 000
}
audit_id() { grep -i "^$AUDIT_HEADER:" "$WORK/headers" | head -1 | cut -d' ' -f2 | tr -d '\r[:space:]'; }

# 조건이 참이 될 때까지 기다린다 — 실패하면 마지막 값을 남긴다.
await() {
  local label="$1" want="$2" probe="$3" got="" i=0
  while [ "$i" -lt "$DEMO_TIMEOUT" ]; do
    got="$(eval "$probe" 2>/dev/null || true)"
    [ "$got" = "$want" ] && { printf '  %-50s %s\n' "$label" "$got"; return 0; }
    i=$((i+1)); sleep 1
  done
  printf '  %-50s %s (기대 %s)\n' "$label" "${got:-∅}" "$want"
  fail "$label 이 ${DEMO_TIMEOUT}초 안에 이뤄지지 않았다."
}

# 감사 행 — 커맨드마다 하나, 결과는 SUCCEEDED 여야 한다(ADR-052 결정 3).
expect_audit() {
  local id="$1" action="$2"
  [ -n "$id" ] || fail "응답에 $AUDIT_HEADER 가 없다 — nginx 가 지웠거나 ops-api 가 싣지 않았다(ADR-057 결정 2)."
  local row
  row="$(aq "SELECT action || '/' || result || '/' || actor FROM audit_logs WHERE id = '$id'")"
  printf '  %-50s %s\n' "감사 행 $id" "$row"
  [ "$row" = "$action/SUCCEEDED/demo-operator" ] || fail "감사 행이 $action/SUCCEEDED/demo-operator 가 아니다: '$row'"
}

# -----------------------------------------------------------------------------
step "0. 스택 확인"
curl -sf --max-time 3 -o /dev/null "http://localhost:${OPS_API_PORT}/actuator/health/readiness" \
  && printf '  %-22s READY\n' "ops-api" || fail "ops-api 가 준비되지 않았다. 먼저 'make up' 을 실행해라."
curl -sf --max-time 5 "$WEB/" | grep -q '<div id="root">' \
  && printf '  %-22s %s\n' "ops-web" "$WEB" || fail "ops-web 이 화면을 주지 않는다 ($WEB). make images 가 ops-web 을 빌드했는가?"

OP="$(bash tools/ops-token/ops-token.sh OPS_OPERATOR demo-operator)"
VIEW="$(bash tools/ops-token/ops-token.sh OPS_VIEWER demo-viewer)"

# -----------------------------------------------------------------------------
# 프록시가 두 헤더를 통과시키는지 — 컴포넌트 테스트에는 nginx 가 없어서 이 자리가 유일한 검사다.
step "1. ops-web 프록시 — Authorization 이 지나고, 401·403 이 Problem Details 로 온다"
status="$(web GET /api/v1/camps "")"
code="$(jq -r '.code // empty' < "$WORK/body")"
printf '  %-50s %s %s\n' "토큰 없이 GET /api/v1/camps" "$status" "$code"
[ "$status" = 401 ] && [ "$code" = unauthenticated ] || fail "토큰 없는 조회가 401 unauthenticated 가 아니다: $status $(cat "$WORK/body")"
status="$(web GET /api/v1/camps "$VIEW")"
printf '  %-50s %s\n' "뷰어 토큰으로 GET /api/v1/camps" "$status"
[ "$status" = 200 ] || fail "뷰어 토큰의 조회가 200 이 아니다 — nginx 가 Authorization 을 넘기지 않았다: $status"
status="$(web POST "/api/v1/waves/$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr 'A-Z' 'a-z')/close" "$VIEW" '{"reason":"뷰어는 못 한다"}')"
code="$(jq -r '.code // empty' < "$WORK/body")"
printf '  %-50s %s %s\n' "뷰어 토큰으로 조기 마감" "$status" "$code"
[ "$status" = 403 ] && [ "$code" = forbidden ] || fail "뷰어의 커맨드가 403 forbidden 이 아니다: $status $(cat "$WORK/body")"

# -----------------------------------------------------------------------------
step "2. 주문 → OPEN 웨이브 (sim-runner, 시나리오=ops-demo — 새벽 티어 하나)"
started_at="$(fq "SELECT to_char(now(), 'YYYY-MM-DD\"T\"HH24:MI:SS.MSOF')")"
./gradlew --console=plain -q :tools:sim-runner:bootRun --args="--dawnline.sim.scenario=ops-demo" 2>&1 \
  | grep -E '시나리오|완료|실패' | sed 's/^/  /' || true
"${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" postgres \
  psql -qtAX -U "$POSTGRES_SUPERUSER" -d dawnline_order \
  -c "SELECT id FROM orders WHERE placed_at >= '$started_at'::timestamptz" | sed '/^$/d' > "$WORK/order-ids.txt"
placed="$(wc -l < "$WORK/order-ids.txt" | tr -d ' ')"
printf '  %-50s %s\n' "접수된 주문" "$placed"
[ "$placed" -gt 0 ] || fail "주문이 하나도 들어가지 않았다."
oids="$(sed "s/.*/'&'/" "$WORK/order-ids.txt" | paste -sd, -)"
await "편입된 주문 (fulfillment)" "$placed" "fq \"SELECT count(*) FROM fulfillment_orders WHERE order_id IN ($oids)\""

# 이번 실행의 주문이 가장 많이 든 OPEN 웨이브 — 운영자가 닫을 웨이브다.
pick="$(fq "SELECT w.id || '|' || w.camp_id || '|' || count(*) FROM waves w
              JOIN fulfillment_orders o ON o.wave_id = w.id AND o.order_id IN ($oids)
             WHERE w.status = 'OPEN' GROUP BY w.id, w.camp_id ORDER BY count(*) DESC, w.id LIMIT 1")"
WAVE="${pick%%|*}"; rest="${pick#*|}"; CAMP="${rest%%|*}"; wave_orders="${rest#*|}"
[ -n "$WAVE" ] || fail "이번 실행의 주문이 든 OPEN 웨이브가 없다."
cutoff="$(fq "SELECT to_char(cutoff_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM waves WHERE id = '$WAVE'")"
printf '  %-50s %s (캠프 %s · 주문 %s · 컷오프 %s)\n' "닫을 웨이브" "$WAVE" "$(fq "SELECT code FROM camps WHERE id = '$CAMP'")" "$wave_orders" "$cutoff"

# 대시보드가 그 웨이브를 OPEN 으로 보는가 — 화면이 읽는 조회 그대로.
# 창은 그 컷오프 ± 1시간 — 계산은 DB 가 한다(GNU·BSD date 의 산술이 다르다).
iso() { fq "SELECT to_char((cutoff_at $1) AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM waves WHERE id = '$WAVE'"; }
from="$(iso "- interval '1 hour'")"
to="$(iso "+ interval '1 hour'")"
await "대시보드의 웨이브 상태" "OPEN" \
  "web GET '/api/v1/camps/$CAMP/waves?from=$from&to=$to' \"\$VIEW\" >/dev/null; jq -r --arg w '$WAVE' '.waves[] | select(.waveId == \$w) | .status' < \"\$WORK/body\""

# -----------------------------------------------------------------------------
step "3. 조기 마감 — 운영자 (POST /api/v1/waves/{id}/close)"
status="$(web POST "/api/v1/waves/$WAVE/close" "$OP" '{"reason":"Phase 6 데모 — 운영자 조기 마감"}')"
printf '  %-50s %s %s\n' "응답" "$status" "$(jq -c '{status, closeCause}' < "$WORK/body")"
[ "$status" = 200 ] || fail "조기 마감이 200 이 아니다: $status $(cat "$WORK/body")"
jq -e '.status == "CLOSED" and .closeCause == "MANUAL"' >/dev/null < "$WORK/body" \
  || fail "마감 응답이 CLOSED · MANUAL 이 아니다: $(cat "$WORK/body")"
expect_audit "$(audit_id)" CLOSE_WAVE
printf '  %-50s %s\n' "fulfillment 의 마감 원인" "$(fq "SELECT close_cause FROM waves WHERE id = '$WAVE'")"

# -----------------------------------------------------------------------------
step "4. 계획 결과와 라우트 지도 (GET /waves/{id}/routes · GET /routes/{id})"
await "dispatch 의 PUBLISHED 계획" "1" "dq \"SELECT count(*) FROM route_plans WHERE wave_id = '$WAVE' AND status = 'PUBLISHED'\""
PLAN="$(dq "SELECT id FROM route_plans WHERE wave_id = '$WAVE' AND status = 'PUBLISHED'")"
route_count="$(dq "SELECT count(*) FROM routes WHERE plan_id = '$PLAN'")"
printf '  %-50s %s\n' "계획의 라우트 (dispatch)" "$route_count"
# 전제를 스스로 말한다 — 재배정은 같은 계획의 다른 라우트로만 된다(ReassignStopService).
[ "$route_count" -ge 2 ] || fail "계획의 라우트가 $route_count 개다 — 옮길 곳이 없다. 웨이브 주문 $wave_orders 건이 차 한 대로
  충분했다는 뜻이다. scenarios.yml 의 ops-demo 크기(orders)를 확인해라."
await "지도가 읽는 라우트 수 (읽기 모델)" "$route_count" \
  "web GET '/api/v1/waves/$WAVE/routes' \"\$VIEW\" >/dev/null; jq -r 'select(.planId != null) | .routes | length' < \"\$WORK/body\""
jq -e '.depot.lat and .depot.lng' >/dev/null < "$WORK/body" || fail "지도의 창고 좌표가 없다(V3): $(cat "$WORK/body")"
cp "$WORK/body" "$WORK/wave-routes.json"
jq -r '.routes[] | "  라우트 \(.routeId[0:8])  r\(.revision)  stop \(.stopCount)  \(.costKrw)원\(if .atRisk then "  at-risk" else "" end)"' \
  < "$WORK/wave-routes.json"

# 지도의 stop — dispatch 로 조회 위임(§3.3). 좌표가 있고 수가 요약과 같아야 그림이 된다.
: > "$WORK/candidates.txt"
for rid in $(jq -r '.routes[].routeId' < "$WORK/wave-routes.json"); do
  status="$(web GET "/api/v1/routes/$rid" "$VIEW")"
  [ "$status" = 200 ] || fail "GET /api/v1/routes/$rid 가 $status 다: $(cat "$WORK/body")"
  jq -e '(.stops | length) > 0 and all(.stops[]; (.lat | type) == "number" and (.lng | type) == "number")' \
    >/dev/null < "$WORK/body" || fail "라우트 $rid 의 stop 에 좌표가 없다: $(cat "$WORK/body")"
  # 옮길 후보: PLANNED stop 이고, 옮긴 뒤에도 떠난 라우트가 비지 않는다(비면 route.assigned 가 나가지 않는다).
  jq -r --arg r "$rid" 'select((.stops | length) >= 2) | .stops[] | select(.status == "PLANNED") | "\($r) \(.orderIds[0])"' \
    < "$WORK/body" >> "$WORK/candidates.txt"
done
printf '  %-50s %s\n' "좌표가 있는 라우트 (위임 조회)" "$route_count"

# -----------------------------------------------------------------------------
step "5. stop 재배정 — 운영자 (POST /routes/{id}/stops/{orderId}/reassign)"
read -r FROM ORDER < "$WORK/candidates.txt" || fail "옮길 PLANNED stop 이 없다(stop 이 둘 이상인 라우트에서)."
TO="$(jq -r --arg f "$FROM" '[.routes[].routeId | select(. != $f)][0]' < "$WORK/wave-routes.json")"
from_before="$(dq "SELECT revision FROM routes WHERE id = '$FROM'")"
to_before="$(dq "SELECT revision FROM routes WHERE id = '$TO'")"
printf '  %-50s %s → %s (r%s → r%s)\n' "주문 ${ORDER:0:8}" "${FROM:0:8}" "${TO:0:8}" "$from_before" "$to_before"

# 화면의 재배정 창에는 이유 칸이 없다 — 계약의 본문은 targetRouteId 하나다(ADR-056 결정 2).
status="$(web POST "/api/v1/routes/$FROM/stops/$ORDER/reassign" "$OP" "{\"targetRouteId\":\"$TO\"}")"
printf '  %-50s %s %s\n' "응답" "$status" "$(jq -c '{fromRevision, toRevision}' < "$WORK/body")"
[ "$status" = 200 ] || fail "재배정이 200 이 아니다: $status $(cat "$WORK/body")"
jq -e --argjson f "$((from_before + 1))" --argjson t "$((to_before + 1))" \
  '.fromRevision == $f and .toRevision == $t' >/dev/null < "$WORK/body" \
  || fail "재배정 응답의 revision 이 두 라우트 각각 +1 이 아니다: $(cat "$WORK/body")"
expect_audit "$(audit_id)" REASSIGN_STOP

# 진실 — dispatch DB.
owner="$(dq "SELECT s.route_id FROM route_stop_orders o JOIN route_stops s ON s.id = o.stop_id WHERE o.order_id = '$ORDER'")"
printf '  %-50s %s\n' "주문이 실린 라우트 (dispatch)" "${owner:0:8}"
[ "$owner" = "$TO" ] || fail "dispatch 에서 주문이 대상 라우트에 없다: $owner"
printf '  %-50s r%s · r%s\n' "두 라우트의 revision (dispatch)" \
  "$(dq "SELECT revision FROM routes WHERE id = '$FROM'")" "$(dq "SELECT revision FROM routes WHERE id = '$TO'")"

# 읽기 모델 — route.assigned 개정 둘이 투영됐는가. 지도는 이것을 읽는다.
await "지도의 revision (떠난 · 받은 라우트)" "$((from_before + 1)),$((to_before + 1))" \
  "web GET '/api/v1/waves/$WAVE/routes' \"\$VIEW\" >/dev/null; jq -r --arg f '$FROM' --arg t '$TO' '[(.routes[] | select(.routeId == \$f) | .revision), (.routes[] | select(.routeId == \$t) | .revision)] | join(\",\")' < \"\$WORK/body\""
status="$(web GET "/api/v1/routes/$TO" "$VIEW")"
jq -e --arg o "$ORDER" 'any(.stops[]; .orderIds | index($o))' >/dev/null < "$WORK/body" \
  || fail "받은 라우트의 지도(GET /routes/$TO)에 옮긴 주문이 없다."
printf '  %-50s %s\n' "받은 라우트의 지도에 옮긴 주문" "있다"

# -----------------------------------------------------------------------------
printf '\n'
bold "Phase 6 데모 완료 — 웨이브 조기 마감(감사 SUCCEEDED) → 계획 라우트 $route_count 개 → stop 재배정 r$((from_before + 1)) · r$((to_before + 1))(감사 SUCCEEDED) · 프록시 헤더 둘 통과"
printf '\n'
printf '  운영 화면   %s/#/waves/%s   (토큰: make token ROLE=OPS_OPERATOR)\n' "$WEB" "$WAVE"
printf '\n'
