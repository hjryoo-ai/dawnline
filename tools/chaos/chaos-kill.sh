#!/usr/bin/env bash
# =============================================================================
# 계획 중 크래시 — dispatch 가 웨이브를 계획하다 죽는다 (DESIGN.md §5.3 · §8.4 「dispatch 계획 중 크래시」 행, RB-04, ADR-024 후속 정정)
#
#   make chaos-kill [SCENARIO=ops-demo]
#
# 기대: 죽은 뒤 그 웨이브의 route_plans 행이 없다(REQUESTED 도 PLANNING 도) · 재기동하면 커밋되지 않은 오프셋이 wave.closed 를 다시
# 전달해 PUBLISHED 하나로 끝난다 · 검증 표 V1–V7 (DLQ 0 · 라우트 stop 주문 중복 0).
#
# 계획은 대개 수십 ms 에 끝난다(로컬 route_plans 의 중앙값 14 ms) — 「계획 중에」 죽이려면 창을 벌려야 한다. 이 스크립트는 dispatch DB 의
# routes 에 배타 락을 쥐어 계획을 **결과 쓰기**에서 세운다. 세운 것은 인위지만 죽이는 순간의 상태는 실제다: 계획 트랜잭션이 열려 있고
# 최적화까지 끝났다. 그 상태를 pg_stat_activity 로 **먼저 확인하고**(전제) 컨테이너에 SIGKILL 을 보낸다. 락은 죽인 뒤에 푼다.
#
# 이전 판의 계획(IMPLEMENTATION_PLAN 7-3②)은 「PLANNING 회수 — 정체 판정 90초로 줄여 돌린다」였다. 그 전제가 이 관측으로 깨졌다 —
# 커밋된 PLANNING 이 생기는 경로가 없고(계획 하나는 트랜잭션 하나), 정체 회수는 지웠다(ADR-024 후속 정정). 판정할 값이 없다.
# =============================================================================
set -uo pipefail
. "$(dirname "$0")/lib.sh"

SCENARIO=${SCENARIO:-ops-demo}
OUT=${CHAOS_OUT:-build/chaos}
mkdir -p "$OUT"
STATE="$OUT/kill.state"
REPORT="$OUT/chaos-kill.md"
: > "$REPORT"
EXPECT_ORDERS=$(orders_of "$SCENARIO")
[[ "$EXPECT_ORDERS" =~ ^[0-9]+$ ]] || { echo "시나리오 $SCENARIO 의 주문 수를 읽지 못했다" >&2; exit 2; }
LOCKER_TAG="chaos-kill-locker"

release_lock() {
  sqlv dispatch "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity
                  WHERE datname = 'dawnline_dispatch' AND application_name = '$LOCKER_TAG'" >/dev/null 2>&1
}
restore() { release_lock; dc up -d dispatch-service >/dev/null 2>&1; }
trap restore EXIT

row() { echo "| $(ts) | $1 | $2 |" | tee -a "$REPORT"; }

say "전제 — 스택이 떠 있다"
[[ "$(dc ps --format '{{.State}}' dispatch-service)" == running ]] || { echo "dispatch-service 가 떠 있지 않다" >&2; exit 1; }
prom_preflight || exit 1

{
  echo "## chaos-kill — dispatch 가 계획 중에 죽는다"
  echo
  echo "시나리오 \`${SCENARIO}\`(주문 ${EXPECT_ORDERS}) · 창을 벌리는 방법: dispatch DB \`routes\` 에 배타 락(결과 쓰기에서 세운다) · 죽이는 방법: \`docker kill -s KILL\`"
  echo "· 회수 경로: 롤백 + \`wave.closed\` 재전달(정체 회수 없음 — ADR-024 후속 정정)"
  echo
  echo "| 시각 | 단계 | 값 |"
  echo "|---|---|---|"
} | tee -a "$REPORT"

tools/chaos/verify.sh baseline "$STATE" || exit 1
say "주문 ${EXPECT_ORDERS}건 (SCENARIO=$SCENARIO)"
make -s smoke SCENARIO="$SCENARIO" > "$OUT/kill-smoke.log" 2>&1 || { echo "smoke 실패 — $OUT/kill-smoke.log" >&2; exit 1; }
# 계획할 후보가 다 들어온 뒤에 닫는다 — 덜 들어온 웨이브를 닫으면 늦은 후보가 계획 밖에 남아 이 카오스와 무관한 것을 잰다.
tools/chaos/verify.sh check "$STATE" --kind "chaos-kill · 마감 전" --wait 300 --expect-orders "$EXPECT_ORDERS" >/dev/null \
  || { echo "주문이 후보까지 가지 않았다 — 카오스 전에 이미 틀렸다" >&2; tools/chaos/verify.sh check "$STATE" --expect-orders "$EXPECT_ORDERS"; exit 1; }

wave=$(earliest_open_wave 20)
[[ -n "$wave" ]] || { echo "주문 20건 이상인 OPEN 웨이브가 없다" >&2; exit 1; }
n_wave=$(sqlv fulfillment "SELECT count(*) FROM fulfillment_orders WHERE wave_id = '$wave' AND status = 'PLANNED'")
row "웨이브" "$wave · 주문 $n_wave"

# 창을 벌린다 — 락을 쥔 세션을 뒤로 돌린다(끝에서 release_lock 이 끊는다).
dc exec -T -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" -e PGAPPNAME="$LOCKER_TAG" postgres \
  psql -U "$POSTGRES_SUPERUSER" -d dawnline_dispatch -c "BEGIN; LOCK TABLE routes IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(600); COMMIT;" \
  > /dev/null 2>&1 &
held() { [[ "$(sqlv dispatch "SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid = l.relation JOIN pg_stat_activity a ON a.pid = l.pid
                               WHERE c.relname = 'routes' AND l.mode = 'AccessExclusiveLock' AND l.granted AND a.application_name = '$LOCKER_TAG'")" == 1 ]]; }
wait_until 30 "routes 배타 락" held || exit 1

read -r code audit <<< "$(operator_close "$wave" "7-3 chaos-kill — 계획을 부르는 조기 마감" "$OUT/command.json")"
row "운영자 커맨드 CLOSE_WAVE" "응답 $code · 감사 $audit"

# 전제를 먼저 말한다 — dispatch 가 그 웨이브의 계획 트랜잭션을 연 채 결과 쓰기에서 기다린다. 이것이 없으면 「죽인 뒤 남은 것 0」은
# 계획이 시작도 안 한 상태를 본 것일 수 있다.
blocked() { [[ -n "$(sqlv dispatch "SELECT pid FROM pg_stat_activity WHERE datname = 'dawnline_dispatch' AND usename = 'dawnline_dispatch'
                                    AND wait_event_type = 'Lock' AND xact_start IS NOT NULL AND query ILIKE 'insert into routes%' LIMIT 1")" ]]; }
wait_until 120 "dispatch 가 결과 쓰기에서 멈췄다" blocked; premise=$?
xact=$(sqlv dispatch "SELECT round(extract(epoch FROM now() - xact_start)::numeric, 1) FROM pg_stat_activity
                       WHERE usename = 'dawnline_dispatch' AND wait_event_type = 'Lock' AND query ILIKE 'insert into routes%' LIMIT 1")
row "전제 — 계획 트랜잭션이 결과 쓰기에서 기다린다" "$([[ $premise == 0 ]] && echo "예 · 트랜잭션 ${xact}s 째" || echo 아니오)"
seen_before=$(sqlv dispatch "SELECT count(*) FROM route_plans WHERE wave_id = '$wave'")
row "그 순간 다른 세션에서 본 route_plans(그 웨이브)" "$seen_before"

docker kill -s KILL "$(dc ps -q dispatch-service)" >/dev/null
killed_at=$SECONDS
row "dispatch-service" "SIGKILL"
release_lock
sleep 3
left=$(sqlv dispatch "SELECT count(*) FROM route_plans WHERE wave_id = '$wave'")
planning=$(sqlv dispatch "SELECT count(*) FROM route_plans WHERE status IN ('REQUESTED', 'PLANNING')")
row "죽인 뒤 — 그 웨이브의 route_plans · 전체 REQUESTED/PLANNING" "$left · $planning"
row "컨테이너" "$(docker inspect -f '{{.State.Status}}' "$(dc ps -aq dispatch-service)")"

say "재기동"
dc up -d dispatch-service >/dev/null 2>&1
started_at=$SECONDS
published() { [[ "$(sqlv dispatch "SELECT status FROM route_plans WHERE wave_id = '$wave'")" == PUBLISHED ]]; }
wait_until 300 "그 웨이브의 계획이 PUBLISHED" published; done_=$?
took=$((SECONDS - started_at))
row "재기동 뒤 PUBLISHED 까지" "$([[ $done_ == 0 ]] && echo "${took}s" || echo "300s 안에 아니다")"
plans=$(sqlv dispatch "SELECT count(*) || ' · 라우트 ' || (SELECT count(*) FROM routes r WHERE r.plan_id = p.id) FROM route_plans p WHERE wave_id = '$wave' GROUP BY p.id")
row "그 웨이브의 계획 · 라우트" "${plans:-없음}"
how=$(logs dispatch-service 10m | grep -c "웨이브 계획: waveId=$wave 결과=PUBLISHED")
row "리스너가 다시 받은 wave.closed 로 계획했다(로그 「웨이브 계획 … PUBLISHED」)" "$how"

say "검증 표"
tools/chaos/verify.sh check "$STATE" --kind "chaos-kill · 재기동 뒤" --wait 300 --expect-orders "$EXPECT_ORDERS" --expect-dlq 0 --out "$REPORT"
verified=$?

check "전제 — 죽이는 순간 계획 트랜잭션이 결과 쓰기에서 열려 있었다(${xact:-모름}s)" "$([[ $premise == 0 ]] && echo ok)"
check "죽인 뒤 그 웨이브의 행이 없다 — REQUESTED 도 PLANNING 도(${left:-모름} · 전체 ${planning:-모름})" "$([[ $left == 0 && $planning == 0 ]] && echo ok)"
check "재기동 뒤 다시 받은 wave.closed 가 PUBLISHED 로 끝냈다(${took}s, 계획 · 라우트 ${plans:-없음})" "$([[ $done_ == 0 && $how -ge 1 ]] && echo ok)"
check "검증 표(DLQ 0 · 라우트 stop 주문 중복 0 · 전부 처리 포함)" "$([[ $verified == 0 ]] && echo ok)"
observe "운영자 조기 마감 — 응답 $code · 감사 $audit (코어가 떠 있을 때 보냈다 — UNKNOWN 을 기대하지 않는다)"
printf '\n### chaos-kill 판정\n\n%s' "$verdicts" | tee -a "$REPORT"
say "보고 — $REPORT"
exit $fail
