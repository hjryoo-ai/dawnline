#!/usr/bin/env bash
# =============================================================================
# DB 장애 카오스 — 서비스 하나의 DB 가 멈춘다 (DESIGN.md §8.4 · §4.6 「경계」, RB-02, ADR-015 후속 정정)
#
#   make chaos-db [SERVICE=fulfillment] [SCENARIO=ops-demo] [HOLD=300]
#
# 기대: DLQ 0건 · 복구 뒤 주문 전부 처리 · 재시도 카운터가 올랐고 나이 게이지가 올랐다가 0 으로 돌아온다.
#
# 장애를 만드는 방법 — 그 서비스의 계정 로그인을 막고 세션을 끊는다(RB-02 §3 의 재현과 같다). postgres 컨테이너를 멈추면 다섯
# 서비스가 같이 멈춰 「서비스 하나」가 아니게 되고, 그 서비스의 outbox 릴레이도 같은 이유로 멈춘다(§8.4 — 같은 사건이다).
# 복구는 이 스크립트가 끝에서 반드시 한다(trap) — 중간에 멈춰도 계정이 NOLOGIN 으로 남지 않는다.
#
# 알림은 실제 Prometheus 에 묻는다(/api/v1/alerts) — 규칙 파일이 적재된 채 그 식이 이 장애에서 실제로 우는가.
# =============================================================================
set -uo pipefail
. "$(dirname "$0")/lib.sh"

SERVICE=${SERVICE:-fulfillment}; SERVICE=${SERVICE%-service}   # logs 처럼 fulfillment-service 로 불러도 된다
SCENARIO=${SCENARIO:-ops-demo}
HOLD=${HOLD:-300}
ROLE="dawnline_${SERVICE}"
APP="${SERVICE}-service"
OUT=${CHAOS_OUT:-build/chaos}
mkdir -p "$OUT"
STATE="$OUT/db.state"
REPORT="$OUT/chaos-db.md"
: > "$REPORT"

orders_of() { awk -v s="$1:" '$1 == s { f = 1 } f && $1 == "orders:" { print $2; exit }' tools/sim-runner/src/main/resources/scenarios.yml; }
EXPECT_ORDERS=$(orders_of "$SCENARIO")
[[ "$EXPECT_ORDERS" =~ ^[0-9]+$ ]] || { echo "시나리오 $SCENARIO 의 주문 수를 읽지 못했다" >&2; exit 2; }

restore() { sql admin "ALTER ROLE $ROLE LOGIN" >/dev/null 2>&1; }
trap restore EXIT

retry_sum() { promv "sum(dawnline_event_retry_total{service=\"$APP\", reason=~\"db_.*\"})"; }
sample() {
  local lag_now lag_sum age retries dlq_state lag_alert
  lag_now=$(promv "max(max by (service, client_id) (kafka_consumer_fetch_manager_records_lag_max{service=\"$APP\"}))")
  lag_sum=$(promv "sum(kafka_consumer_fetch_manager_records_lag{service=\"$APP\", topic=~\".+[.].+\"})")
  age=$(promv "max(dawnline_event_retry_age_seconds{service=\"$APP\"})")
  retries=$(retry_sum)
  dlq_state=$(promv "sum(dawnline_event_processed_total{service=\"$APP\", outcome=\"dlq\"})")
  lag_alert=$(alert_state DawnlineConsumerLag)
  printf '| %s | %s | %s | %s | %s | %s | %s | %s |\n' "$(ts)" "$1" "${lag_now:-—}" "${lag_sum:-—}" "${age:-—}" "${retries:-—}" \
    "${dlq_state:-—}" "${lag_alert:-inactive}" | tee -a "$REPORT"
}

say "전제 — 스택이 떠 있고 $ROLE 이 로그인할 수 있다"
[[ "$(sqlv admin "SELECT rolcanlogin FROM pg_roles WHERE rolname = '$ROLE'")" == t ]] || { echo "$ROLE 이 이미 NOLOGIN 이다 — 앞 실행을 확인한다" >&2; exit 1; }
curl -sf "http://localhost:$PROMETHEUS_PORT/-/ready" >/dev/null || { echo "Prometheus 가 없다" >&2; exit 1; }
[[ -n "$(promv "max(dawnline_event_retry_age_seconds{service=\"$APP\"})")" ]] \
  || { echo "$APP 의 dawnline_event_retry_age_seconds 가 없다 — 이미지가 7-3 이전이다(make images)" >&2; exit 1; }

{
  echo "## chaos-db — ${APP} 의 DB 가 ${HOLD}초 멈춘다"
  echo
  echo "시나리오 \`${SCENARIO}\`(주문 ${EXPECT_ORDERS}) · 장애 방법: \`ALTER ROLE ${ROLE} NOLOGIN\` + 세션 종료 · 알림은 실제 Prometheus(\`/api/v1/alerts\`)"
  echo
  echo "| 시각 | 단계 | 랙 — 지금 식 \`max … records_lag_max\` | 랙 — 합 \`sum … records_lag{topic=~\".+[.].+\"}\` | 재시도 나이(s) | 재시도 db_* 누계 | DLQ 누계 | \`DawnlineConsumerLag\` |"
  echo "|---|---|---|---|---|---|---|---|"
} | tee -a "$REPORT"

tools/chaos/verify.sh baseline "$STATE" || exit 1
retries_before=$(retry_sum); retries_before=${retries_before:-0}
sample "기준"

say "장애 — $ROLE 로그인 금지 + 세션 종료"
sql admin "ALTER ROLE $ROLE NOLOGIN" >/dev/null
sql admin "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE usename = '$ROLE'" >/dev/null

say "주문 ${EXPECT_ORDERS}건 (SCENARIO=$SCENARIO)"
make -s smoke SCENARIO="$SCENARIO" > "$OUT/smoke.log" 2>&1 || { echo "smoke 실패 — $OUT/smoke.log" >&2; exit 1; }
sample "주문 끝"

# 운영자 커맨드 하나 — 장애 중인 코어에 조기 마감을 보낸다. 적용될 수 없다(그 DB 에 아무도 못 들어간다). 감사 행이 무엇으로 남는지는
# 그대로 본다 — UNKNOWN 이 자연히 생기면 7-3b 의 해소 경로가 닫는다. 만들지 않는다(IMPLEMENTATION_PLAN 7-3).
if [[ "$SERVICE" == fulfillment ]]; then
  wave=$(sqlv fulfillment "SELECT w.id FROM waves w WHERE w.status = 'OPEN' AND NOT EXISTS (
            SELECT 1 FROM waves e WHERE e.status = 'OPEN' AND e.camp_id = w.camp_id AND e.service_tier = w.service_tier
               AND e.cutoff_at < w.cutoff_at) ORDER BY w.cutoff_at LIMIT 1")
  if [[ -n "$wave" ]]; then
    token=$(bash tools/ops-token/ops-token.sh OPS_OPERATOR chaos-operator)
    code=$(curl -s -o "$OUT/command.json" -w '%{http_code}' --max-time 90 -X POST \
      -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
      -d '{"reason":"7-3 chaos-db — 장애 중인 코어에 보낸 조기 마감"}' \
      "http://localhost:$OPS_API_PORT/api/v1/waves/$wave/close")
    audit=$(sqlv ops "SELECT result FROM audit_logs WHERE action = 'CLOSE_WAVE' AND target_id = '$wave' ORDER BY created_at DESC LIMIT 1")
    echo "| $(ts) | 운영자 커맨드 CLOSE_WAVE $wave | 응답 $code | 감사 ${audit:-없음} | | | | |" | tee -a "$REPORT"
  fi
fi

max_age=0; lag_fired=""
end=$((SECONDS + HOLD))
while (( SECONDS < end )); do
  sleep 30
  sample "장애 중"
  a=$(promv "max(dawnline_event_retry_age_seconds{service=\"$APP\"})"); a=${a%.*}
  (( ${a:-0} > max_age )) && max_age=${a:-0}
  [[ "$(alert_state DawnlineConsumerLag)" == firing ]] && lag_fired=yes
done

say "장애 중의 검증 표 — 빠진 주문이 있어야 한다(검사가 유실을 볼 수 있다는 표본)"
tools/chaos/verify.sh check "$STATE" --kind "chaos-db · 장애 중" --expect-orders "$EXPECT_ORDERS" --out "$REPORT" >/dev/null
during=$?

say "복구 — $ROLE 로그인 허용"
restore
for i in 1 2 3 4; do sleep 30; sample "복구 뒤"; done

say "검증 표"
tools/chaos/verify.sh check "$STATE" --kind "chaos-db · 복구 뒤" --wait 600 --expect-orders "$EXPECT_ORDERS" --expect-dlq 0 --out "$REPORT"
verified=$?
retries_after=$(retry_sum); retries_after=${retries_after:-0}
age_after=$(promv "max(dawnline_event_retry_age_seconds{service=\"$APP\"})")

fail=0
check() { if [[ "$2" == ok ]]; then echo "- ✅ $1"; else echo "- ✗ $1"; fail=1; fi; }
{
  echo
  echo "### chaos-db 판정"
  echo
  check "검증 표(DLQ 0 · 전부 처리 포함)" "$([[ $verified == 0 ]] && echo ok)"
  check "장애 중의 검증 표는 빠진 주문을 봤다 — 검사가 유실을 볼 수 있다" "$([[ $during != 0 ]] && echo ok)"
  check "재시도 카운터(db_*)가 올랐다: ${retries_before} → ${retries_after}" "$(awk -v a="$retries_before" -v b="$retries_after" 'BEGIN { if (b > a) print "ok" }')"
  check "재시도 나이가 올랐다(최대 ${max_age}s)" "$([[ $max_age -gt 60 ]] && echo ok)"
  check "재시도 나이가 0 으로 돌아왔다(${age_after:-모름})" "$([[ "${age_after:-x}" == 0 ]] && echo ok)"
  echo "- 관찰: \`DawnlineConsumerLag\` 가 장애 중 firing 에 닿았나 — ${lag_fired:-아니다}"
} | tee -a "$REPORT"
say "보고 — $REPORT"
exit $fail
