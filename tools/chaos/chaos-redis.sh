#!/usr/bin/env bash
# =============================================================================
# Redis 장애 카오스 — Redis 가 멈춘다 (DESIGN.md §7.2 폴백 표 · §8.4 「Redis 다운」 행, RB-03, ADR-027 후속 정정의 재검토 지점)
#
#   make chaos-redis [SCENARIO=ops-demo] [HOLD=300]
#
# 기준 — ADR-027 후속 정정: 릴레이의 조정자가 Redis 가 아니라 자기 DB 의 advisory lock 이므로 「발행이 멈추지 않고 **지연도 오르지
# 않아야 한다**」. 재기 전에 적는다(2026-09-26 — 이 문단이 첫 실행보다 먼저 커밋됐다):
#   ① 발행이 멈추지 않는다 — 장애가 **끝나기 전에** 검증 표 V1(빠진 주문 0) · V7(outbox 미발행 · 격리 0)이 ✅ 다. 주문 전부가 Redis 없이
#      후보까지 간다.
#   ② 지연이 오르지 않는다 — 장애 중 `max(dawnline_outbox_lag_seconds)` 의 최댓값이 **5초 이하**. 알림 문턱(30초)의 1/6 이고, 릴레이는
#      100 ms 마다 돈다 — Redis 가 발행 경로에 있으면 5초는 한 번의 명령 타임아웃으로도 넘는다.
#   ③ 폴백이 보인다 — `DawnlineRateLimitBypassed` 가 실제 Prometheus 에서 울고(RB-03 의 알림) `dawnline_geo_lookups_total{outcome="bypassed"}`
#      가 오른다. 폴백은 조용히 일어나면 안 된다(§7.2).
#   ④ 복구 뒤 — 레이트 리밋의 bypassed 가 더 오르지 않고, 검증 표 V1–V7(DLQ 0)이 ✅.
#
# Redis 는 멈춘다(dc stop redis — 볼륨은 그대로). 복구는 끝에서 반드시 한다(trap).
# =============================================================================
set -uo pipefail
. "$(dirname "$0")/lib.sh"

SCENARIO=${SCENARIO:-ops-demo}
HOLD=${HOLD:-300}
LAG_LIMIT=5
OUT=${CHAOS_OUT:-build/chaos}
mkdir -p "$OUT"
STATE="$OUT/redis.state"
REPORT="$OUT/chaos-redis.md"
: > "$REPORT"
EXPECT_ORDERS=$(orders_of "$SCENARIO")
[[ "$EXPECT_ORDERS" =~ ^[0-9]+$ ]] || { echo "시나리오 $SCENARIO 의 주문 수를 읽지 못했다" >&2; exit 2; }

restore() { dc start redis >/dev/null 2>&1; }
trap restore EXIT

bypassed_rate_limit() { promv 'sum(dawnline_rate_limit_decisions_total{outcome="bypassed"})'; }
bypassed_geo() { promv 'sum(dawnline_geo_lookups_total{outcome="bypassed"})'; }
sample() {
  local lag geo rl loaded alert
  lag=$(promv 'max(dawnline_outbox_lag_seconds)')
  geo=$(bypassed_geo); rl=$(bypassed_rate_limit)
  loaded=$(promv 'min(dawnline_geo_index_loaded)')
  alert=$(alert_state DawnlineRateLimitBypassed)
  printf '| %s | %s | %s | %s | %s | %s | %s |\n' "$(ts)" "$1" "${lag:-—}" "${rl:-—}" "${geo:-—}" "${loaded:-—}" "${alert:-inactive}" | tee -a "$REPORT"
}

say "전제 — 스택이 떠 있고 Redis 가 산다"
[[ "$(dc exec -T redis redis-cli ping 2>/dev/null | tr -d '\r')" == PONG ]] || { echo "redis 가 응답하지 않는다" >&2; exit 1; }
prom_preflight || exit 1

{
  echo "## chaos-redis — Redis 가 ${HOLD}초 멈춘다"
  echo
  echo "시나리오 \`${SCENARIO}\`(주문 ${EXPECT_ORDERS}) · 장애 방법: \`docker compose stop redis\` · 기준: ADR-027 후속 정정(발행이 멈추지 않고 지연이 ${LAG_LIMIT}초를 넘지 않는다)"
  echo
  echo "| 시각 | 단계 | outbox 지연 최대(s) | 레이트 리밋 bypassed 누계 | GEO bypassed 누계 | GEO 적재(min) | \`DawnlineRateLimitBypassed\` |"
  echo "|---|---|---|---|---|---|---|"
} | tee -a "$REPORT"

tools/chaos/verify.sh baseline "$STATE" || exit 1
geo_before=$(bypassed_geo); geo_before=${geo_before:-0}
sample "기준"

say "장애 — Redis 정지"
dc stop redis >/dev/null 2>&1
stopped_at=$SECONDS

# 지연은 주문이 흐르는 동안 잰다 — 뒤에서 30초마다가 아니라 5초마다(최댓값을 놓치지 않게).
max_lag=0
( while :; do promv 'max(dawnline_outbox_lag_seconds)'; sleep 5; done ) > "$OUT/redis-lag.txt" &
sampler=$!
say "주문 ${EXPECT_ORDERS}건 (SCENARIO=$SCENARIO) — Redis 없이"
make -s smoke SCENARIO="$SCENARIO" > "$OUT/smoke.log" 2>&1; smoke=$?
sample "주문 끝"

wave=$(earliest_open_wave)
code=""; audit=""
if [[ -n "$wave" ]]; then
  read -r code audit <<< "$(operator_close "$wave" "7-3 chaos-redis — Redis 가 없는 동안의 조기 마감" "$OUT/command.json")"
  echo "| $(ts) | 운영자 커맨드 CLOSE_WAVE $wave | 응답 $code · 감사 $audit | | | | |" | tee -a "$REPORT"
fi

# ① — 장애가 끝나기 전에 흐름이 끝나야 한다. 남은 장애 시간만큼 기다린다.
remaining=$(( HOLD - (SECONDS - stopped_at) )); (( remaining < 60 )) && remaining=60
say "장애 중의 검증 표 — Redis 없이 전부 처리됐어야 한다(① 발행이 멈추지 않는다)"
tools/chaos/verify.sh check "$STATE" --kind "chaos-redis · 장애 중" --wait "$remaining" --expect-orders "$EXPECT_ORDERS" --expect-dlq 0 --out "$REPORT" >/dev/null
during=$?
fired=""
while (( SECONDS - stopped_at < HOLD )); do sleep 15; [[ "$(alert_state DawnlineRateLimitBypassed)" == firing ]] && fired=yes; done
[[ "$(alert_state DawnlineRateLimitBypassed)" == firing ]] && fired=yes
sample "장애 끝"
kill "$sampler" 2>/dev/null
max_lag=$(awk 'NF { v = $1 + 0; if (v > m) m = v } END { printf "%.3f", m + 0 }' "$OUT/redis-lag.txt")
n_lag=$(awk 'NF' "$OUT/redis-lag.txt" | wc -l | tr -d ' ')
geo_during=$(bypassed_geo); geo_during=${geo_during:-0}

say "복구 — Redis 시작"
restore
pong() { [[ "$(dc exec -T redis redis-cli ping 2>/dev/null | tr -d '\r')" == PONG ]]; }
wait_until 60 "Redis PONG" pong
sleep 30
rl_1=$(bypassed_rate_limit)
for i in 1 2 3 4; do sleep 30; sample "복구 뒤"; done
rl_2=$(bypassed_rate_limit)
# 복구 뒤의 요청 — 레이트 리밋이 다시 판정하는지 보려면 요청이 있어야 한다. 주문 하나(시나리오 tiny)가 아니라 조회로 충분하지 않다 —
# 레이트 리밋은 주문 접수에만 있다. bypassed 가 멈췄다는 것은 요청이 없어도 참이므로 allowed 가 오르는 것까지 본다.
allowed_1=$(promv 'sum(dawnline_rate_limit_decisions_total{outcome="allowed"})')
make -s smoke SCENARIO=tiny > "$OUT/smoke-after.log" 2>&1
sleep 20
allowed_2=$(promv 'sum(dawnline_rate_limit_decisions_total{outcome="allowed"})')
rl_3=$(bypassed_rate_limit)

say "검증 표"
tools/chaos/verify.sh check "$STATE" --kind "chaos-redis · 복구 뒤" --wait 300 --expect-dlq 0 --out "$REPORT"
verified=$?

check "① 발행이 멈추지 않는다 — 장애가 끝나기 전에 검증 표 ✅(주문 ${EXPECT_ORDERS} 전부 후보까지 · outbox 0/0)" "$([[ $during == 0 ]] && echo ok)"
check "② 지연이 오르지 않는다 — 장애 중 outbox 지연 최대 ${max_lag}s ≤ ${LAG_LIMIT}s (5초마다 ${n_lag}번 잼)" \
  "$(awk -v m="$max_lag" -v l="$LAG_LIMIT" -v n="$n_lag" 'BEGIN { if (n > 0 && m <= l) print "ok" }')"
check "③ 폴백이 보인다 — \`DawnlineRateLimitBypassed\` firing" "$([[ -n $fired ]] && echo ok)"
check "③ 폴백이 보인다 — GEO bypassed ${geo_before} → ${geo_during}" "$(awk -v a="$geo_before" -v b="$geo_during" 'BEGIN { if (b > a) print "ok" }')"
check "④ 복구 뒤 레이트 리밋이 다시 판정한다 — bypassed ${rl_1:-모름} → ${rl_3:-모름} 그대로 · allowed ${allowed_1:-모름} → ${allowed_2:-모름}" \
  "$(awk -v a="${rl_1:-x}" -v b="${rl_3:-y}" -v c="${allowed_1:-0}" -v d="${allowed_2:-0}" 'BEGIN { if (a == b && d > c) print "ok" }')"
check "④ 검증 표(DLQ 0 · 전부 처리 포함)" "$([[ $verified == 0 ]] && echo ok)"
observe "운영자 조기 마감 — 응답 ${code:-보내지 않음} · 감사 ${audit:-—} (수동 마감은 Redis 락을 쓰지 않는다 — ADR-054)"
printf '\n### chaos-redis 판정\n\n%s' "$verdicts" | tee -a "$REPORT"
say "보고 — $REPORT"
exit $fail
