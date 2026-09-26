#!/usr/bin/env bash
# =============================================================================
# Kafka 장애 카오스 — 브로커가 멈춘다 (DESIGN.md §8.4 「Kafka 다운」 행 · §8.6 레디니스, RB-01, ADR-015 · ADR-016)
#
#   make chaos-kafka [SCENARIO=ops-demo] [HOLD=300]
#
# 기대:
#   - 브로커가 없는 동안에도 주문 API 가 받는다 — 쓰기 경로는 outbox 이고 레디니스에 브로커가 없다(ADR-016)
#   - outbox 지연이 오르고 DawnlineOutboxLag(가장 오래된 미발행 > 30초)가 실제 Prometheus 에서 운다
#   - 브로커 부재는 일시적이다 — outbox 가 격리하지 않는다(ADR-015 원 결정, V7 의 격리 0)
#   - 복구 뒤 outbox 가 비고 주문 전부 처리 · DLQ 0 · 알림이 꺼진다
#
# 브로커는 멈춘다(dc stop kafka — 컨테이너와 볼륨은 그대로). 복구는 끝에서 반드시 한다(trap).
# =============================================================================
set -uo pipefail
. "$(dirname "$0")/lib.sh"

SCENARIO=${SCENARIO:-ops-demo}
HOLD=${HOLD:-300}
OUT=${CHAOS_OUT:-build/chaos}
mkdir -p "$OUT"
STATE="$OUT/kafka.state"
REPORT="$OUT/chaos-kafka.md"
: > "$REPORT"
EXPECT_ORDERS=$(orders_of "$SCENARIO")
[[ "$EXPECT_ORDERS" =~ ^[0-9]+$ ]] || { echo "시나리오 $SCENARIO 의 주문 수를 읽지 못했다" >&2; exit 2; }

restore() { dc start kafka >/dev/null 2>&1; }
trap restore EXIT

sample() {
  local lag unpublished leader alert
  lag=$(promv 'max(dawnline_outbox_lag_seconds)')
  unpublished=$(promv 'sum(dawnline_outbox_unpublished)')
  leader=$(promv 'sum(dawnline_outbox_leader == 1)')
  alert=$(alert_state DawnlineOutboxLag)
  printf '| %s | %s | %s | %s | %s | %s |\n' "$(ts)" "$1" "${lag:-—}" "${unpublished:-—}" "${leader:-—}" "${alert:-inactive}" | tee -a "$REPORT"
}

say "전제 — 스택이 떠 있고 브로커가 산다"
[[ "$(dc ps --format '{{.State}}' kafka)" == running ]] || { echo "kafka 가 떠 있지 않다" >&2; exit 1; }
prom_preflight || exit 1

{
  echo "## chaos-kafka — 브로커가 ${HOLD}초 멈춘다"
  echo
  echo "시나리오 \`${SCENARIO}\`(주문 ${EXPECT_ORDERS}) · 장애 방법: \`docker compose stop kafka\` · 알림은 실제 Prometheus(\`/api/v1/alerts\`)"
  echo
  echo "| 시각 | 단계 | outbox 지연 최대(s) | 미발행 합 | 릴레이 리더 수 | \`DawnlineOutboxLag\` |"
  echo "|---|---|---|---|---|---|"
} | tee -a "$REPORT"

tools/chaos/verify.sh baseline "$STATE" || exit 1
sample "기준"

say "장애 — 브로커 정지"
dc stop kafka >/dev/null 2>&1
stopped_at=$SECONDS

say "주문 ${EXPECT_ORDERS}건 (SCENARIO=$SCENARIO) — 브로커 없이"
make -s smoke SCENARIO="$SCENARIO" > "$OUT/kafka-smoke.log" 2>&1; smoke=$?
sample "주문 끝"

# 운영자 커맨드 하나 — 코어(fulfillment)는 떠 있고 마감은 DB + outbox 라 받아야 한다. wave.closed 는 복구 뒤에 나가 계획까지 간다.
wave=$(earliest_open_wave)
code=""; audit=""
if [[ -n "$wave" ]]; then
  read -r code audit <<< "$(operator_close "$wave" "7-3 chaos-kafka — 브로커가 없는 동안의 조기 마감" "$OUT/command.json")"
  echo "| $(ts) | 운영자 커맨드 CLOSE_WAVE $wave | 응답 $code · 감사 $audit | | | |" | tee -a "$REPORT"
fi

max_lag=0; fired=""
while (( SECONDS - stopped_at < HOLD )); do
  sleep 30
  sample "장애 중"
  l=$(promv 'max(dawnline_outbox_lag_seconds)'); l=${l%.*}
  (( ${l:-0} > max_lag )) && max_lag=${l:-0}
  [[ "$(alert_state DawnlineOutboxLag)" == firing ]] && fired=yes
done

say "장애 중의 검증 표 — 빠진 주문이 있어야 한다(검사가 유실을 볼 수 있다는 표본)"
tools/chaos/verify.sh check "$STATE" --kind "chaos-kafka · 장애 중" --expect-orders "$EXPECT_ORDERS" --out "$REPORT" >/dev/null
during=$?

say "복구 — 브로커 시작"
restore
healthy() { [[ "$(docker inspect -f '{{.State.Health.Status}}' "$(dc ps -q kafka)")" == healthy ]]; }
wait_until 180 "브로커 healthy" healthy
for i in 1 2 3 4; do sleep 30; sample "복구 뒤"; done
lag_after=$(promv 'max(dawnline_outbox_lag_seconds)')
alert_after=$(alert_state DawnlineOutboxLag)

say "검증 표"
tools/chaos/verify.sh check "$STATE" --kind "chaos-kafka · 복구 뒤" --wait 600 --expect-orders "$EXPECT_ORDERS" --expect-dlq 0 --out "$REPORT"
verified=$?

check "브로커 없이 주문 API 가 받았다 — 주문 ${EXPECT_ORDERS}건 생성(smoke 종료 $smoke)" "$([[ $smoke == 0 ]] && echo ok)"
check "outbox 지연이 올랐다(최대 ${max_lag}s > 30)" "$([[ $max_lag -gt 30 ]] && echo ok)"
check "\`DawnlineOutboxLag\` 가 실제 Prometheus 에서 firing 에 닿았다" "$([[ -n $fired ]] && echo ok)"
check "장애 중의 검증 표는 빠진 주문을 봤다 — 검사가 유실을 볼 수 있다" "$([[ $during != 0 ]] && echo ok)"
check "복구 뒤 outbox 지연이 5초 아래로 · 알림이 꺼졌다(${lag_after:-모름}s · ${alert_after:-inactive})" \
  "$(awk -v l="${lag_after:-99}" -v a="${alert_after:-inactive}" 'BEGIN { if (l < 5 && a == "inactive") print "ok" }')"
check "검증 표(DLQ 0 · outbox 격리 0 · 전부 처리 포함)" "$([[ $verified == 0 ]] && echo ok)"
observe "운영자 조기 마감 — 응답 ${code:-보내지 않음} · 감사 ${audit:-—} (코어는 떠 있다 — 브로커 없이 받는다)"
printf '\n### chaos-kafka 판정\n\n%s' "$verdicts" | tee -a "$REPORT"
say "보고 — $REPORT"
exit $fail
