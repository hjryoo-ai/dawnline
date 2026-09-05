#!/usr/bin/env bash
# =============================================================================
# make demo — Phase 2 데모 (IMPLEMENTATION_PLAN Phase 2 DoD)
#
# 보이려는 것 한 줄: **주문이 웨이브에 편입되고, 컷오프가 오면 캠프별로 wave.closed 가
# 정확히 한 번 브로커에 도착한다.**
#
# 이 스크립트는 "성공했다" 고 말하기 전에 DB 와 Kafka 를 모두 읽는다. 둘 중 하나만 보면
# 안 되는 이유는 Phase 1 에서 배운 것과 같다 — outbox 에 행이 있는 것과 브로커에 레코드가
# 있는 것은 다른 사실이고, 그 사이에 릴레이와 봉투 조립이 있다.
#
#   DEMO_ORDERS   주문 수 (기본 200, scenarios.yml 의 smoke)
#   DEMO_TIMEOUT  각 대기 단계의 상한 초 (기본 120)
# =============================================================================
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

ENV_FILE="deploy/compose/.env"
COMPOSE=(docker compose -f deploy/compose/docker-compose.yml --env-file "$ENV_FILE")

set -a; . "$ENV_FILE"; set +a

DEMO_TIMEOUT="${DEMO_TIMEOUT:-120}"
SCENARIO="${SCENARIO:-smoke}"
TOPIC=dawnline.wave.closed.v1
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
step() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }
fail() { printf '\n\033[31m실패: %s\033[0m\n\n' "$*" >&2; exit 1; }

# fulfillment DB 를 읽는다. 값 하나만 필요하므로 -tA (튜플만·정렬 없음).
fq() {
  "${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" postgres \
    psql -qtAX -U "$POSTGRES_SUPERUSER" -d dawnline_fulfillment -c "$1"
}
oq() {
  "${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" postgres \
    psql -qtAX -U "$POSTGRES_SUPERUSER" -d dawnline_order -c "$1"
}
# 사람이 볼 표. fq 와 달리 머리글을 남긴다.
ftable() {
  "${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" postgres \
    psql -qX -U "$POSTGRES_SUPERUSER" -d dawnline_fulfillment -P border=0 -P footer=off -c "$1" \
    | sed 's/^/  /'
}

# 소비가 멈춘 것과 느린 것은 랙으로만 구별된다. 기다리다 실패할 때 이것을 같이 찍지 않으면
# "왜 안 왔는지" 를 다시 조사해야 한다.
lag() {
  printf '  컨슈머 랙:\n'
  "${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
      --bootstrap-server localhost:9092 --describe --group fulfillment-service 2>/dev/null \
    | awk 'NR>1 && $6 ~ /^[0-9]+$/ { l[$2] += $6 } END { for (t in l) printf "    %-32s %s\n", t, l[t] }' \
    || printf '    (조회 실패)\n'
}

# 조건이 참이 될 때까지 기다린다. 상한을 넘기면 이유와 마지막 값을 함께 남기고 죽는다 —
# "타임아웃" 세 글자만 남기면 다음 사람이 처음부터 다시 조사한다.
await() {
  local label="$1" want="$2" sql="$3" got=""
  local i=0
  while [ "$i" -lt "$DEMO_TIMEOUT" ]; do
    got="$(fq "$sql" | tr -d '[:space:]')"
    [ "$got" = "$want" ] && { printf '  %-46s %s\n' "$label" "$got"; return 0; }
    i=$((i+1)); sleep 1
  done
  printf '  %-46s %s (기대 %s)\n' "$label" "${got:-∅}" "$want"
  lag
  fail "$label 이 ${DEMO_TIMEOUT}초 안에 이뤄지지 않았다. 위의 실제값과 컨슈머 랙이 어디서 멈췄는지 말해 준다."
}

# -----------------------------------------------------------------------------
step "0. 스택 확인"
for pair in "order-service:$ORDER_SERVICE_PORT" "fulfillment-service:$FULFILLMENT_SERVICE_PORT"; do
  url="http://localhost:${pair##*:}/actuator/health/readiness"
  if curl -sf --max-time 3 -o /dev/null "$url"; then
    printf '  %-22s READY\n' "${pair%%:*}"
  else
    fail "${pair%%:*} 가 준비되지 않았다 ($url). 먼저 'make up' 또는 'make up-lean' 을 실행해라."
  fi
done

# -----------------------------------------------------------------------------
# 시드는 Flyway R__seed_fulfillment.sql 이 기동 때 넣는다(ADR-021). 데모가 넣지 않는다 —
# 시드가 마이그레이션이면 통합 테스트도 같은 시드를 자동으로 얻기 때문이다.
step "1. 시드 확인 (Flyway R__seed_fulfillment)"
printf '  %-22s %s\n' "fulfillment_centers" "$(fq 'SELECT count(*) FROM fulfillment_centers')"
printf '  %-22s %s\n' "camps"               "$(fq 'SELECT count(*) FROM camps')"
zones="$(fq 'SELECT count(*) FROM zones' | tr -d '[:space:]')"
printf '  %-22s %s\n' "zones"               "$zones"
[ "$zones" -ge 91 ] || fail "권역 시드가 91개 미만이다($zones). UNSERVICEABLE 이 시드 부족으로 나온다 (ADR-021)."

# -----------------------------------------------------------------------------
# 워밍업. Phase 1 k6 실측(docs/benchmarks/phase1-orders-k6.md)에서 콜드 p99 가 1.9~4.0 초로
# 나왔다 — 0.75 CPU 에서 SerialGC 와 JIT 가 겹치는 구간이다. 워밍업 없이 본 시나리오를 돌리면
# 첫 몇 건이 sim-runner 의 5초 타임아웃에 걸려 "주문 200건 중 199건" 으로 죽는다.
# 콜드 스타트는 Phase 7 항목으로 열려 있고(§8.3 판정 기록), 데모는 그 사실을 숨기지 않되
# **웨이브 마감을 보이는 일**은 그것과 별개로 성립해야 하므로 여기서 한 번 데운다.
# 이 단계의 실패는 무시한다 — 데우는 것이 목적이고, 몇 건이 들어갔는지는 세지 않는다.
step "2. 워밍업 (콜드 스타트 흡수 — 실패해도 넘어간다)"
./gradlew --console=plain -q :tools:sim-runner:bootRun \
  --args="--dawnline.sim.scenario=tiny" >/dev/null 2>&1 \
  && echo "  워밍업 완료" || echo "  워밍업 중 일부 실패 (무시)"

# -----------------------------------------------------------------------------
# 기준선. 데모를 두 번 돌려도 이번 실행분만 세기 위해서다. 워밍업 뒤에 잡는다.
step "3. 기준선"
base_orders="$(fq 'SELECT count(*) FROM fulfillment_orders' | tr -d '[:space:]')"
base_waves="$(fq 'SELECT count(*) FROM waves' | tr -d '[:space:]')"
started_at="$(fq "SELECT to_char(now(), 'YYYY-MM-DD\"T\"HH24:MI:SS.MSOF')" | tr -d '[:space:]')"
printf '  %-22s %s\n' "fulfillment_orders" "$base_orders"
printf '  %-22s %s\n' "waves"              "$base_waves"

# -----------------------------------------------------------------------------
step "4. 주문 생성 (sim-runner, 시나리오=$SCENARIO)"
./gradlew --console=plain -q :tools:sim-runner:bootRun \
  --args="--dawnline.sim.scenario=$SCENARIO" 2>&1 | sed 's/^/  /'

# 이번 실행이 만든 주문 ID 를 그대로 들고 간다. "전체 행 수 = 기준선 + 200" 으로 기다리면
# 브로커에 남아 있던 이전 실행분(k6 벤치마크 등)이 함께 들어오는 순간 기대값이 어긋나고,
# 진짜 원인은 "편입이 안 됐다" 가 아니라 "다른 것도 같이 왔다" 인데 그 구별이 사라진다.
oq "SELECT id FROM orders WHERE placed_at >= '$started_at'::timestamptz ORDER BY id" \
  | tr -d '\r' | sed '/^$/d' | sort > "$WORK/order-ids.txt"
placed="$(wc -l < "$WORK/order-ids.txt" | tr -d ' ')"
printf '  %-22s %s\n' "접수된 주문" "$placed"
[ "$placed" -gt 0 ] || fail "order-service 에 주문이 하나도 들어가지 않았다."
oids="$(sed "s/.*/'&'/" "$WORK/order-ids.txt" | paste -sd, -)"

# -----------------------------------------------------------------------------
# order.placed → fulfillment 편입. 여기까지가 §5.2 의 FC 선택·웨이브 편입이다.
step "5. 웨이브 편입 대기 (order.placed 소비)"
await "이번 실행분 fulfillment_orders" "$placed" \
      "SELECT count(*) FROM fulfillment_orders WHERE order_id IN ($oids)"

new_waves="$(fq "SELECT count(*) FROM waves" | tr -d '[:space:]')"
printf '  %-46s %s\n' "새 웨이브" "$((new_waves - base_waves))"
# DoD 가 금지하는 것은 **시드 부족으로 인한** UNSERVICEABLE 이다(ADR-021). OUT_OF_STOCK 은
# 그 반대다 — R__seed_fulfillment 가 SKU-00013 을 전 FC 품절로, SKU-00666 을 1개로 일부러
# 넣어 §5.2 3단계 재고 필터가 실제로 도는지 보이려는 결손이고, sim-runner 는 SKU 를 2,000개
# 공간에서 고르므로 200건에 몇 건은 그 둘을 뽑는다. 둘을 한 숫자로 합쳐 0을 요구하면
# "시드가 덜 됐다" 와 "시드가 의도대로 됐다" 가 구별되지 않는다.
unserv_seed="$(fq "SELECT count(*) FROM fulfillment_orders
                    WHERE order_id IN ($oids) AND status = 'UNSERVICEABLE'
                      AND unserviceable_reason <> 'OUT_OF_STOCK'" | tr -d '[:space:]')"
unserv_stock="$(fq "SELECT count(*) FROM fulfillment_orders
                     WHERE order_id IN ($oids) AND unserviceable_reason = 'OUT_OF_STOCK'" | tr -d '[:space:]')"
printf '  %-46s %s\n' "UNSERVICEABLE (시드 부족 — 0이어야 한다)" "$unserv_seed"
printf '  %-46s %s\n' "UNSERVICEABLE (OUT_OF_STOCK — 시드가 의도한 결손)" "$unserv_stock"
if [ "$unserv_seed" -ne 0 ]; then
  ftable "SELECT unserviceable_reason, count(*) FROM fulfillment_orders
           WHERE order_id IN ($oids) AND status = 'UNSERVICEABLE'
             AND unserviceable_reason <> 'OUT_OF_STOCK' GROUP BY 1"
  fail "시드 부족으로 인한 UNSERVICEABLE 이 $unserv_seed 건이다 (ADR-021, Phase 2 DoD)."
fi

printf '\n'
ftable "SELECT c.code || ' / ' || w.service_tier || ' / ' || to_char(w.cutoff_at AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI') AS wave,
               w.status, count(o.order_id) AS \"이번 실행\"
          FROM waves w JOIN camps c ON c.id = w.camp_id
          LEFT JOIN fulfillment_orders o ON o.wave_id = w.id AND o.order_id IN ($oids)
         WHERE w.status = 'OPEN'
         GROUP BY 1, 2 ORDER BY 1"

# -----------------------------------------------------------------------------
# 컷오프를 기다릴 수는 없다. §2.2 의 컷오프는 10:00·14:00·자정이고, 그 표는
# libs/common 의 TierSchedule 하나뿐이다(ADR-020 후속 정정 2) — "데모용 짧은 컷오프 표" 를
# 만들면 ADR-020 이 없애려던 두 번째 복사본이 바로 그것이 된다.
#
# 그래서 표가 아니라 **시계를 당긴다**: 이번 실행이 만든 웨이브의 cutoff_at 을 과거로 민다.
# 마감 판정은 그대로 스케줄러가 하고(30초 주기, cutoff_at + grace <= now), Redis 락도
# FOR UPDATE 도 실제 경로를 그대로 지난다. 데모가 건드리는 것은 "언제" 뿐이다.
step "6. 컷오프 당기기 (데모 전용 — 표가 아니라 시각을 민다)"
fq "WITH pushed AS (
       UPDATE waves SET cutoff_at = now() - interval '10 minutes'
        WHERE status = 'OPEN' AND cutoff_at > now()
          AND id IN (SELECT DISTINCT wave_id FROM fulfillment_orders WHERE order_id IN ($oids))
        RETURNING id)
     SELECT id FROM pushed" | tr -d '\r' | sed '/^$/d' | sort > "$WORK/wave-ids.txt"
pushed="$(wc -l < "$WORK/wave-ids.txt" | tr -d ' ')"
printf '  %-46s %s\n' "과거로 민 OPEN 웨이브" "$pushed"
[ "$pushed" -gt 0 ] || fail "밀 수 있는 OPEN 웨이브가 없다."
ids="$(sed "s/.*/'&'/" "$WORK/wave-ids.txt" | paste -sd, -)"

# -----------------------------------------------------------------------------
# 마감은 스케줄러가 한다 — 데모가 직접 닫지 않는다. 여기서 지나는 경로가
# Redis 락 → FOR UPDATE → OPEN→CLOSING→CLOSED → outbox → 릴레이로, 운영과 같다.
# 상태를 CLOSED 로 못 박지 않는다 (2026-09-05 정정). Phase 3 에서 dispatch 가 wave.closed 를
# 소비해 계획을 돌리고, 그 결과 plan.completed 가 웨이브를 CLOSED → PLANNED 로 옮긴다
# (ADR-024). 즉 "CLOSED 인 웨이브가 29개" 는 **dispatch 가 없을 때만** 참인 문장이었고,
# 이 데모는 그 사실에 기대고 있었다 — Phase 3 이 붙자마자 조용히 깨졌다.
#
# 마감이 이뤄졌다는 사실은 "OPEN·CLOSING 을 벗어났다" 로 말한다. 그 뒤로 얼마나 더 갔는지는
# 다음 단계(phase3-demo.sh)가 본다.
step "7. 마감 대기 (CloseDueWavesService, 30초 주기 + grace)"
await "마감된 웨이브 (OPEN·CLOSING 을 벗어남)" "$pushed" \
      "SELECT count(*) FROM waves WHERE status NOT IN ('OPEN','CLOSING') AND id IN ($ids)"
await "미발행 outbox"  "0"       "SELECT count(*) FROM outbox_events WHERE published_at IS NULL"

ftable "SELECT c.code || ' / ' || w.service_tier AS wave, w.status, w.order_count
          FROM waves w JOIN camps c ON c.id = w.camp_id
         WHERE w.id IN ($ids) ORDER BY 1"

# -----------------------------------------------------------------------------
# DB 가 CLOSED 라고 말하는 것과 브로커에 레코드가 있는 것은 다른 사실이다. 그 사이에
# outbox 릴레이와 봉투 조립이 있고, Phase 1 에서 그 구간을 따로 검증한 이유가 이것이다.
step "8. 브로커 확인 ($TOPIC)"
"${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic "$TOPIC" \
  --from-beginning --timeout-ms 15000 \
  --property print.key=true --property key.separator=$'\t' \
  > "$WORK/closed.tsv" 2>/dev/null || true

# 키(campId)와 payload.campId 가 같은지까지 본다 — §4.1 이 이 토픽의 키를 campId 로 정한
# 이유가 캠프 단위 순서 보장이고, 키가 어긋나면 그 보장이 조용히 사라진다.
: > "$WORK/seen.txt"
while IFS=$'\t' read -r key value; do
  [ -n "${value:-}" ] || continue
  wid="$(jq -r '.payload.waveId' <<<"$value")"
  grep -qxF "$wid" "$WORK/wave-ids.txt" || continue
  cid="$(jq -r '.payload.campId' <<<"$value")"
  [ "$key" = "$cid" ] || fail "wave.closed 의 파티션 키가 campId 가 아니다: key=$key campId=$cid (§4.1)"
  jq -e '.eventType == "wave.closed" and .schemaVersion == 1 and .payload.orderCount >= 0' >/dev/null <<<"$value" \
    || fail "wave.closed 봉투가 계약과 다르다: $value"
  echo "$wid" >> "$WORK/seen.txt"
done < "$WORK/closed.tsv"

dupes="$(sort "$WORK/seen.txt" | uniq -d | wc -l | tr -d ' ')"
seen="$(sort -u "$WORK/seen.txt" | wc -l | tr -d ' ')"
printf '  %-46s %s / %s\n' "브로커에 도착한 wave.closed (고유 웨이브)" "$seen" "$pushed"
printf '  %-46s %s\n'      "중복 발행된 웨이브" "$dupes"
[ "$seen" = "$pushed" ] || fail "마감한 웨이브 $pushed 개 중 $seen 개만 브로커에 도착했다."
[ "$dupes" = "0" ]      || fail "이중 마감이다 — 같은 웨이브의 wave.closed 가 두 번 나갔다."

# orderCount 는 마감 시 집계여야 한다(ADR-025) — DB 의 실제 편입 수와 대조한다.
mismatch="$(fq "SELECT count(*) FROM waves w
                 WHERE w.id IN ($ids)
                   AND w.order_count <> (SELECT count(*) FROM fulfillment_orders o WHERE o.wave_id = w.id)" | tr -d '[:space:]')"
printf '  %-46s %s\n' "order_count 가 실제 편입 수와 다른 웨이브" "$mismatch"
[ "$mismatch" = "0" ] || fail "order_count 가 마감 시 집계값이 아니다 (ADR-025)."

# -----------------------------------------------------------------------------
# 다음 단계(phase3-demo.sh)가 이어받는다. $WORK 는 종료 시 지워지므로 저장소 안에 남긴다 —
# 두 스크립트를 하나로 합치지 않는 이유는 Phase 경계가 곧 "무엇까지 되는가" 의 경계이고,
# Phase 3 이 깨졌을 때 Phase 2 데모는 여전히 돌아야 하기 때문이다.
mkdir -p build/demo
cp "$WORK/wave-ids.txt" build/demo/wave-ids.txt

# -----------------------------------------------------------------------------
step "9. 메트릭 (§9.1)"
curl -sf "http://localhost:$FULFILLMENT_SERVICE_PORT/actuator/prometheus" \
  | grep -E '^dawnline_(wave_orders|promise_revised_total|fc_fallback_total|geo_index_loaded|event_rejected_total)' \
  | sed 's/^/  /' || echo "  (해당 메트릭 없음)"

# -----------------------------------------------------------------------------
printf '\n'
bold "데모 완료 — 주문 $placed 건(편입 $((placed - unserv_stock)) · 재고결손 $unserv_stock) → 웨이브 $pushed 개 마감 → wave.closed $seen 건 (중복 0)"
printf '\n'
printf '  Grafana     http://localhost:%s  (%s / %s)\n' "$GRAFANA_PORT" "$GRAFANA_ADMIN_USER" "$GRAFANA_ADMIN_PASSWORD"
printf '  Prometheus  http://localhost:%s\n' "$PROMETHEUS_PORT"
printf '  Swagger     http://localhost:%s/swagger-ui.html\n' "$ORDER_SERVICE_PORT"
printf '\n'
