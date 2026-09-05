#!/usr/bin/env bash
# =============================================================================
# make demo 의 뒷부분 — Phase 3 데모 (IMPLEMENTATION_PLAN Phase 3 DoD)
#
# phase2-demo.sh 가 wave.closed 까지 보였다. 여기서 보이려는 것 한 줄:
# **닫힌 웨이브가 라우트가 되고, 운영자가 GET /api/v1/plans/{id} 하나로 비용·미배정·설명을
# 볼 수 있으며, 그 설명이 냉장 주문이 냉장 차량에만 실렸음을 말한다.**
#
# 앞 스크립트와 같은 규칙을 따른다: DB 와 브로커를 **둘 다** 읽고, 기다리다 실패하면
# 마지막 값과 컨슈머 랙을 함께 남긴다.
#
# 웨이브 id 는 phase2-demo.sh 가 build/demo/wave-ids.txt 에 남긴다. 인자로 받은 파일이
# 있으면 그것을 쓴다.
# =============================================================================
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

ENV_FILE="deploy/compose/.env"
COMPOSE=(docker compose -f deploy/compose/docker-compose.yml --env-file "$ENV_FILE")
set -a; . "$ENV_FILE"; set +a

DEMO_TIMEOUT="${DEMO_TIMEOUT:-120}"
WAVE_IDS_FILE="${1:-build/demo/wave-ids.txt}"
TOPIC=dawnline.route.assigned.v1
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
step() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }
fail() { printf '\n\033[31m실패: %s\033[0m\n\n' "$*" >&2; exit 1; }

dq() {
  "${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" postgres \
    psql -qtAX -U "$POSTGRES_SUPERUSER" -d dawnline_dispatch -c "$1"
}
dtable() {
  "${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" postgres \
    psql -qX -U "$POSTGRES_SUPERUSER" -d dawnline_dispatch -P border=0 -P footer=off -c "$1" \
    | sed 's/^/  /'
}

lag() {
  printf '  컨슈머 랙:\n'
  "${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
      --bootstrap-server localhost:9092 --describe --group dispatch-service 2>/dev/null \
    | awk 'NR>1 && $6 ~ /^[0-9]+$/ { l[$2] += $6 } END { for (t in l) printf "    %-32s %s\n", t, l[t] }' \
    || printf '    (조회 실패)\n'
}

await() {
  local label="$1" want="$2" sql="$3" got=""
  local i=0
  while [ "$i" -lt "$DEMO_TIMEOUT" ]; do
    got="$(dq "$sql" | tr -d '[:space:]')"
    [ "$got" = "$want" ] && { printf '  %-46s %s\n' "$label" "$got"; return 0; }
    i=$((i+1)); sleep 1
  done
  printf '  %-46s %s (기대 %s)\n' "$label" "${got:-∅}" "$want"
  lag
  fail "$label 이 ${DEMO_TIMEOUT}초 안에 이뤄지지 않았다. 위의 실제값과 컨슈머 랙이 어디서 멈췄는지 말해 준다."
}

# -----------------------------------------------------------------------------
step "0. 스택 확인"
url="http://localhost:${DISPATCH_SERVICE_PORT}/actuator/health/readiness"
curl -sf --max-time 3 -o /dev/null "$url" \
  && printf '  %-22s READY\n' "dispatch-service" \
  || fail "dispatch-service 가 준비되지 않았다 ($url). 먼저 'make up' 을 실행해라."

[ -s "$WAVE_IDS_FILE" ] \
  || fail "웨이브 id 파일이 없다: $WAVE_IDS_FILE. phase2-demo.sh 를 먼저 돌려라(make demo)."

# 전제: 지금이 차량 근무창 안이어야 한다.
#
# shift_start/end 는 벽시계 TIME 이고 어댑터가 *계획 날짜*에 붙인다(JdbcReferenceData).
# §6.3 의 하드 룰은 "복귀 ≤ 근무 종료 − 30분 버퍼" 이므로, 근무 종료가 한 시간도 안 남았으면
# 어떤 라우트도 실행 가능하지 않고 모든 계획이 NO_CANDIDATES 로 끝난다. 그때 아래 1단계는
# "PUBLISHED 계획 0 (기대 29)" 라고만 말하는데, 그 문장은 원인을 가리키지 않는다.
# 전제는 전제로 확인한다 (CLAUDE.md — 전제를 스스로 말한다).
shift_window="$(dq "SELECT to_char(min(shift_start),'HH24:MI') || ' ' || to_char(max(shift_end),'HH24:MI') FROM vehicles WHERE active")"
now_kst="$(TZ=Asia/Seoul date +%H:%M)"
printf '  %-22s %s (지금 %s KST)\n' "차량 근무창" "$(echo $shift_window | tr ' ' '-')" "$now_kst"
awk -v now="$now_kst" -v w="$shift_window" 'BEGIN {
  split(now, n, ":"); split(w, p, " "); split(p[1], a, ":"); split(p[2], b, ":");
  nm = n[1]*60 + n[2]; sm = a[1]*60 + a[2]; em = b[1]*60 + b[2];
  exit (nm >= sm && nm <= em - 60) ? 0 : 1;
}' || fail "지금($now_kst KST)은 차량 근무창($(echo $shift_window | tr ' ' '-') KST) 밖이거나 끝까지 한 시간이 안 남았다.
  §6.3 은 복귀가 근무 종료 − 30분 버퍼 안이기를 요구하므로, 이 시각에는 실행 가능한 라우트가
  없어 모든 계획이 NO_CANDIDATES 로 끝난다. 데모의 결함이 아니라 시드 근무창의 결과다.
  근무창 안에서 다시 돌려라. (이 창은 CI 에도 걸린다 — IMPLEMENTATION_PLAN Phase 4-12)"
wave_count="$(wc -l < "$WAVE_IDS_FILE" | tr -d ' ')"
ids="$(sed "s/.*/'&'/" "$WAVE_IDS_FILE" | paste -sd, -)"
printf '  %-22s %s\n' "이어받은 웨이브" "$wave_count"

# -----------------------------------------------------------------------------
# wave.closed → dispatch 계획. 여기까지가 §6.5 파이프라인 전체다.
step "1. 계획 대기 (wave.closed 소비 → 룰 → 최적화 → 발행)"
await "PUBLISHED 계획" "$wave_count" \
      "SELECT count(*) FROM route_plans WHERE wave_id IN ($ids) AND status = 'PUBLISHED'"
await "미발행 outbox"  "0" "SELECT count(*) FROM outbox_events WHERE published_at IS NULL"

dtable "SELECT p.strategy, p.mode, p.assigned_count AS 배정, p.unassigned_count AS 미배정,
               p.total_cost_krw AS 비용, p.plan_duration_ms AS \"계획ms\",
               (SELECT count(*) FROM routes r WHERE r.plan_id = p.id) AS 라우트
          FROM route_plans p WHERE p.wave_id IN ($ids) ORDER BY p.started_at"

# -----------------------------------------------------------------------------
# DoD 의 본문 — 운영자 화면 하나로 비용·미배정·설명이 나오는가 (§5.3).
step "2. 계획 조회 (GET /api/v1/plans/{planId})"
plan_id="$(dq "SELECT id FROM route_plans
                WHERE wave_id IN ($ids) AND status = 'PUBLISHED'
                ORDER BY assigned_count DESC LIMIT 1" | tr -d '[:space:]')"
[ -n "$plan_id" ] || fail "조회할 계획이 없다."

curl -sf --max-time 10 "http://localhost:${DISPATCH_SERVICE_PORT}/api/v1/plans/${plan_id}" \
  > "$WORK/plan.json" || fail "GET /api/v1/plans/${plan_id} 가 실패했다."

jq -e '.planId and .status == "PUBLISHED" and (.totalCostKrw | type) == "number"
       and (.unassignedCount | type) == "number" and (.explanations | length) > 0' \
   >/dev/null < "$WORK/plan.json" \
  || fail "계획 조회 응답에 비용·미배정·설명이 모두 들어 있지 않다 (§5.3): $(cat "$WORK/plan.json")"

jq -r '"  계획 \(.planId)\n  전략 \(.strategy) · 모드 \(.mode) · 룰 v\(.ruleVersion)\n  비용 \(.totalCostKrw)원 · 배정 \(.assignedCount) · 미배정 \(.unassignedCount) · 라우트 \(.routes | length) · 설명 \(.explanations | length)줄"' \
  < "$WORK/plan.json"

printf '\n  미배정 사유 (설명에서):\n'
jq -r '[.explanations[] | select(.outcome == "UNASSIGNED")] | group_by(.ruleName)[]
       | "    \(.[0].ruleName // "(사유 없음)")  \(length)건"' < "$WORK/plan.json" \
  || printf '    (미배정 없음)\n'

# -----------------------------------------------------------------------------
# Phase 3 DoD: "냉장 주문이 냉장 차량에만 배정됨을 설명 조회로 확인".
# 설명(plan_explanations)과 차량(vehicles)을 조인해 위반 건수를 센다. 0 이어야 한다.
step "3. 냉장 하드 룰 — 설명으로 확인 (§6.3 cold-chain)"
cold_total="$(dq "SELECT count(*) FROM dispatch_candidates c
                   WHERE c.wave_id IN ($ids) AND c.requires_cold" | tr -d '[:space:]')"
cold_assigned="$(dq "SELECT count(*) FROM plan_explanations e
                       JOIN dispatch_candidates c ON c.order_id = e.order_id
                      WHERE c.wave_id IN ($ids) AND c.requires_cold AND e.outcome = 'ASSIGNED'" | tr -d '[:space:]')"
violations="$(dq "SELECT count(*) FROM plan_explanations e
                    JOIN dispatch_candidates c ON c.order_id = e.order_id
                    JOIN vehicles v ON v.id = e.vehicle_id
                   WHERE c.wave_id IN ($ids) AND c.requires_cold
                     AND e.outcome = 'ASSIGNED' AND NOT v.is_cold" | tr -d '[:space:]')"
warm_used="$(dq "SELECT count(DISTINCT r.vehicle_id) FROM routes r
                   JOIN route_plans p ON p.id = r.plan_id
                   JOIN vehicles v ON v.id = r.vehicle_id
                  WHERE p.wave_id IN ($ids) AND NOT v.is_cold" | tr -d '[:space:]')"

printf '  %-46s %s\n' "냉장 후보" "$cold_total"
printf '  %-46s %s\n' "그중 배정된 것" "$cold_assigned"
printf '  %-46s %s\n' "비냉장 차량에 실린 냉장 주문 (0이어야 한다)" "$violations"
printf '  %-46s %s\n' "쓰인 비냉장 차량 (0이면 위 0은 공허하다)" "$warm_used"

# 전제를 스스로 말한다 — 검사할 것이 없으면 통과가 아니라 실패다 (CLAUDE.md 「폴백 테스트」와 같은 규칙).
[ "$cold_total" -gt 0 ]    || fail "냉장 후보가 하나도 없다. cold-chain 룰이 검사되지 않았다 (sim 의 cold-ratio 확인)."
[ "$cold_assigned" -gt 0 ] || fail "냉장 주문이 하나도 배정되지 않았다. 검사할 것이 없다."
[ "$warm_used" -gt 0 ]     || fail "비냉장 차량이 한 대도 쓰이지 않았다 — '냉장 차량에만' 이 자동으로 참이 된다."
[ "$violations" = "0" ]    || fail "냉장 주문 $violations 건이 비냉장 차량에 실렸다 (§6.3 cold-chain 하드 룰)."

# -----------------------------------------------------------------------------
step "4. 브로커 확인 ($TOPIC)"
"${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic "$TOPIC" \
  --from-beginning --timeout-ms 15000 \
  --property print.key=true --property key.separator=$'\t' \
  > "$WORK/assigned.tsv" 2>/dev/null || true

dq "SELECT r.id FROM routes r JOIN route_plans p ON p.id = r.plan_id
     WHERE p.wave_id IN ($ids)" | tr -d '\r' | sed '/^$/d' | sort > "$WORK/route-ids.txt"
expected_routes="$(wc -l < "$WORK/route-ids.txt" | tr -d ' ')"

: > "$WORK/seen.txt"
while IFS=$'\t' read -r key value; do
  [ -n "${value:-}" ] || continue
  rid="$(jq -r '.payload.routeId' <<<"$value")"
  grep -qxF "$rid" "$WORK/route-ids.txt" || continue
  # §4.1 이 이 토픽의 키를 routeId 로 정한 이유가 라우트 단위 순서다. 어긋나면 조용히 사라진다.
  [ "$key" = "$rid" ] || fail "route.assigned 의 파티션 키가 routeId 가 아니다: key=$key routeId=$rid (§4.1)"
  jq -e '.eventType == "route.assigned" and .schemaVersion == 1
         and .payload.revision >= 1 and (.payload.stops | length) == .payload.summary.stopCount' \
     >/dev/null <<<"$value" || fail "route.assigned 봉투가 계약과 다르다: $value"
  echo "$rid" >> "$WORK/seen.txt"
done < "$WORK/assigned.tsv"

seen="$(sort -u "$WORK/seen.txt" | wc -l | tr -d ' ')"
printf '  %-46s %s / %s\n' "브로커에 도착한 route.assigned (고유 라우트)" "$seen" "$expected_routes"
[ "$seen" = "$expected_routes" ] || fail "라우트 $expected_routes 개 중 $seen 개만 브로커에 도착했다."

# -----------------------------------------------------------------------------
step "5. 메트릭 (§9.1)"
curl -sf "http://localhost:$DISPATCH_SERVICE_PORT/actuator/prometheus" \
  | grep -E '^dawnline_(plan_duration_seconds_count|plan_cost_krw|plan_unassigned|plan_degraded_total|cancel_too_late_total|outbox_leader)' \
  | sed 's/^/  /' || echo "  (해당 메트릭 없음)"

# -----------------------------------------------------------------------------
printf '\n'
bold "Phase 3 데모 완료 — 웨이브 $wave_count 개 → 계획 $wave_count 개 → 라우트 $expected_routes 개 → route.assigned $seen 건 · 냉장 위반 0"
printf '\n'
printf '  계획 조회   http://localhost:%s/api/v1/plans/%s\n' "$DISPATCH_SERVICE_PORT" "$plan_id"
printf '  Grafana     http://localhost:%s\n' "$GRAFANA_PORT"
printf '\n'
