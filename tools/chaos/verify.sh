#!/usr/bin/env bash
# =============================================================================
# 검증 표 V1–V7 — 카오스 넷과 7-4 peak-day 가 같은 표를 낸다 (DESIGN.md §13 「카오스」, IMPLEMENTATION_PLAN 7-3)
#
#   verify.sh baseline <상태파일>                         기준을 남긴다 — 시각 T0 · DLQ 끝 오프셋
#   verify.sh check <상태파일> [--kind 이름] [--wait 초] [--expect-orders N] [--expect-dlq N]
#                              [--expect-unserviceable 사유=N[,사유=N…]] [--out 파일]
#
# check 는 V1(유실)과 V7(outbox)이 맞을 때까지 --wait 초 동안 다시 잰다 — 장애 뒤 밀린 것이 빠지는 시간을 준다.
# V1 은 전제를 먼저 말한다 — T0 이후 주문이 없으면 「빠진 주문 0」은 빈 집합끼리의 비교다(§13 축 10). --expect-orders 가 있으면
# 그 수와 같아야 하고, 없으면 1 이상이어야 한다.
# 배차 불가는 한 수로 더하지 않고 **사유별로** 기대값과 견준다 — 합 하나는 이유가 바뀐 회귀를 삼킨다(7-3① 의 1차 177 건은
# 앞선 데모가 닫아 둔 웨이브 때문의 MAX_PUSHES_EXCEEDED 176 이었고, 합은 「빠진 주문 0」으로 그것을 통과시켰다). 사유의 기대:
#   흐름 사유(STALE_PLACED · MAX_PUSHES_EXCEEDED) — 정상 실행에서 0. 고칠 수 없다
#   OUT_OF_STOCK — 시드의 재고 예외(inventory_stock)와 주문 줄에서 파생한다. 수가 아니라 주문 집합이 같아야 한다
#   그 밖의 데이터 사유(NO_ZONE_MATCH · NO_ELIGIBLE_FC …) — 0. 시나리오가 일부러 내면 --expect-unserviceable 로 적는다
# 표는 카오스 종류와 무관하게 같다. 판정이 하나라도 ✗ 면 종료 코드 1.
#
# 어느 칸도 「결과가 없다」를 0 으로 접지 않는다 — 셀 수 없으면 「모름」이고 ✗ 다(§9.1 「없는 시계열은 0 으로 보인다」).
# =============================================================================
set -uo pipefail
. "$(dirname "$0")/lib.sh"

SERVICES_DB=(order fulfillment dispatch tracking ops)

dlq_end_sum() {
  dc exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic '.*\.dlq' 2>/dev/null \
    | awk -F: 'NF == 3 { s += $3; n++ } END { if (n == 0) print ""; else print s }'
}

cmd=${1:-}; state=${2:-}
[[ -n "$cmd" && -n "$state" ]] || { sed -n '3,12p' "$0"; exit 2; }
shift 2

if [[ "$cmd" == baseline ]]; then
  t0=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  dlq=$(dlq_end_sum)
  [[ -n "$dlq" ]] || { echo "DLQ 끝 오프셋을 읽지 못했다 — 기준을 남기지 않는다" >&2; exit 1; }
  printf 't0=%s\ndlq=%s\n' "$t0" "$dlq" > "$state"
  say "기준 — T0=$t0 · DLQ 끝 오프셋 합 $dlq → $state"
  exit 0
fi
[[ "$cmd" == check ]] || { echo "알 수 없는 명령: $cmd" >&2; exit 2; }

kind=chaos; wait_s=0; expect_dlq=""; expect_orders=""; expect_unsv=""; out=""
FLOW_REASONS="STALE_PLACED MAX_PUSHES_EXCEEDED"
while [[ $# -gt 0 ]]; do
  case $1 in
    --kind) kind=$2; shift 2 ;;
    --wait) wait_s=$2; shift 2 ;;
    --expect-dlq) expect_dlq=$2; shift 2 ;;
    --expect-orders) expect_orders=$2; shift 2 ;;
    --expect-unserviceable) expect_unsv=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
. "$state"
for pair in ${expect_unsv//,/ }; do
  reason=${pair%%=*}
  case " $FLOW_REASONS OUT_OF_STOCK " in
    *" $reason "*) echo "--expect-unserviceable 로 $reason 의 기대를 바꿀 수 없다 — 흐름 사유는 0, OUT_OF_STOCK 은 시드에서 파생한다" >&2; exit 2 ;;
  esac
  [[ "$pair" == *=* && "${pair#*=}" =~ ^[0-9]+$ ]] || { echo "--expect-unserviceable 의 형식: 사유=N — $pair" >&2; exit 2; }
done
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

# --- V1 유실 — 주문 = 후보 + 취소 + 배차 불가 -------------------------------------------------------------
# 주문 하나의 파이프라인 끝은 셋이다: dispatch 의 후보가 됐다 · 계획 전에 취소됐다 · fulfillment 가 배차 불가로 판정했다.
# 서비스 사이에 JOIN 이 없으므로(불변규칙 3) id 집합을 뽑아 빼고, 어느 끝에도 없는 주문을 센다.
v1() {
  sqlv order       "SELECT id FROM orders WHERE placed_at >= '$t0' ORDER BY 1" > "$tmp/orders"
  # 하류의 행은 T0 주문 집합과 교집합한다 — 자기 시각(created_at)만으로 거르면 T0 **전**에 들어와 T0 **뒤**에 처리된 주문(앞 시나리오가
  # 남긴 밀림)이 끼어든다. 2026-09-26 첫 chaos 워크플로에서 chaos-db 가 중간에 끝나 남긴 주문이 chaos-kafka 의 T0 직후 처리되어
  # OUT_OF_STOCK 집합 비교가 1 ↔ 2 로 빨갰다(근거: 관측(재현됨) — 그 실행의 보고). created_at >= T0 은 상위집합이라 미리 거르는 데만 쓴다.
  sqlv dispatch    "SELECT order_id FROM dispatch_candidates WHERE created_at >= '$t0' ORDER BY 1" > "$tmp/cand_t0"
  comm -12 "$tmp/orders" "$tmp/cand_t0" > "$tmp/cand"
  sqlv order       "SELECT id FROM orders WHERE placed_at >= '$t0' AND status = 'CANCELLED' ORDER BY 1" > "$tmp/cancel_all"
  sqlv fulfillment "SELECT order_id FROM fulfillment_orders WHERE created_at >= '$t0' AND status = 'UNSERVICEABLE' ORDER BY 1" > "$tmp/unsv_t0"
  comm -12 "$tmp/orders" "$tmp/unsv_t0" > "$tmp/unsv_all"
  comm -23 "$tmp/cancel_all" "$tmp/cand" > "$tmp/cancel"
  sort -u "$tmp/cand" "$tmp/cancel" > "$tmp/c_or_x"
  comm -23 "$tmp/unsv_all" "$tmp/c_or_x" > "$tmp/unsv"
  sort -u "$tmp/c_or_x" "$tmp/unsv" > "$tmp/ends"
  comm -23 "$tmp/orders" "$tmp/ends" > "$tmp/missing"
  n_order=$(wc -l < "$tmp/orders" | tr -d ' ')
  n_cand=$(wc -l < "$tmp/cand" | tr -d ' ')
  n_cancel=$(wc -l < "$tmp/cancel" | tr -d ' ')
  n_unsv=$(wc -l < "$tmp/unsv" | tr -d ' ')
  n_missing=$(wc -l < "$tmp/missing" | tr -d ' ')
  v1_reasons
}

# --- V1 의 배차 불가 — 사유별 -------------------------------------------------------------------------------
# 줄마다 「사유|값|기대|판정」. 사유가 비어 있는 행은 「사유 없음」으로 따로 센다(모름 — ✗).
v1_reasons() {
  sqlv fulfillment "SELECT order_id || '|' || coalesce(unserviceable_reason, '(사유 없음)') FROM fulfillment_orders
                     WHERE created_at >= '$t0' AND status = 'UNSERVICEABLE' ORDER BY 1" > "$tmp/unsv_reason_all"
  awk -F'|' 'NR == FNR { keep[$1] = 1; next } keep[$1]' "$tmp/unsv" "$tmp/unsv_reason_all" > "$tmp/unsv_reason"

  # OUT_OF_STOCK 의 기대 — 재고 스텁은 예외 행만 적고 차감하지 않는다(R__seed_fulfillment). 어느 FC 에서든 어떤 줄이 예외 행의
  # 수량을 넘으면 그 FC 는 못 채운다. 모든 FC 가 못 채우는 주문이 기대 집합이다.
  sqlv fulfillment "SELECT 'S|' || fc_id || '|' || sku || '|' || available_qty FROM inventory_stock
                    UNION ALL SELECT 'F|' || id FROM fulfillment_centers" > "$tmp/stock"
  local skus
  skus=$(awk -F'|' '$1 == "S" { printf "%s'"'"'%s'"'"'", (n++ ? "," : ""), $3 }' "$tmp/stock")
  : > "$tmp/lines"
  [[ -n "$skus" ]] && sqlv order "SELECT i.order_id || '|' || i.sku || '|' || sum(i.qty) FROM order_items i JOIN orders o ON o.id = i.order_id
                                   WHERE o.placed_at >= '$t0' AND i.sku IN ($skus) GROUP BY i.order_id, i.sku" > "$tmp/lines"
  awk -F'|' '
    FNR == NR { if ($1 == "S") avail[$2 "|" $3] = $4; else fcs[$2] = 1; next }
    { need[$1 "|" $2] = $3; skus_of[$1] = skus_of[$1] " " $2 }
    END {
      for (o in skus_of) {
        n = split(skus_of[o], s, " "); ok = 0
        for (fc in fcs) {
          fits = 1
          for (i = 1; i <= n; i++) if ((fc "|" s[i]) in avail && need[o "|" s[i]] > avail[fc "|" s[i]]) fits = 0
          if (fits) { ok = 1; break }
        }
        if (!ok) print o
      }
    }' "$tmp/stock" "$tmp/lines" | sort > "$tmp/oos_derived"
  comm -23 "$tmp/oos_derived" "$tmp/c_or_x" > "$tmp/oos_expected"
  awk -F'|' '$2 == "OUT_OF_STOCK" { print $1 }' "$tmp/unsv_reason" | sort > "$tmp/oos_actual"

  local reasons r n expect verdict kind_of
  reasons=$( { printf '%s\n' OUT_OF_STOCK $FLOW_REASONS; cut -d'|' -f2 "$tmp/unsv_reason";
               for pair in ${expect_unsv//,/ }; do echo "${pair%%=*}"; done; } | awk 'NF && !seen[$0]++')
  # 줄마다 「사유|종류|값|기대|판정」 — 표는 판정을 모은 뒤에 그린다(아래 「표」).
  : > "$tmp/reason_rows"
  while IFS= read -r r; do
    n=$(awk -F'|' -v r="$r" '$2 == r' "$tmp/unsv_reason" | wc -l | tr -d ' ')
    case " $FLOW_REASONS " in *" $r "*) kind_of="흐름"; expect=0 ;; *) kind_of="데이터"; expect=0 ;; esac
    if [[ "$r" == OUT_OF_STOCK ]]; then
      kind_of="시드 재고 예외"; expect=$(wc -l < "$tmp/oos_expected" | tr -d ' ')
      if cmp -s "$tmp/oos_expected" "$tmp/oos_actual"; then verdict=ok; else verdict=bad; fi
      expect="${expect} — 주문 줄 × inventory_stock 에서 파생 · 주문 집합이 같아야 한다"
    else
      for pair in ${expect_unsv//,/ }; do [[ "${pair%%=*}" == "$r" ]] && expect=${pair#*=}; done
      [[ "$r" == "(사유 없음)" ]] && kind_of="모름"
      verdict=$( [[ "$n" == "$expect" ]] && echo ok || echo bad)
    fi
    echo "$r|$kind_of|$n|$expect|$verdict" >> "$tmp/reason_rows"
  done <<< "$reasons"
}

# --- V7 outbox — 미발행 · 격리가 남지 않았다 ----------------------------------------------------------------
v7() {
  v7_detail=""; v7_bad=0
  for db in "${SERVICES_DB[@]}"; do
    row=$(sqlv "$db" "SELECT count(*) FILTER (WHERE published_at IS NULL AND failed_at IS NULL) || '/' || count(*) FILTER (WHERE failed_at IS NOT NULL) FROM outbox_events")
    [[ -n "$row" ]] || { row="모름"; v7_bad=1; }
    [[ "$row" == "0/0" ]] || v7_bad=1
    v7_detail+="$db $row · "
  done
  v7_detail=${v7_detail% · }
}

start=$SECONDS
while :; do
  v1; v7
  if { [[ "$n_missing" == 0 && "$v7_bad" == 0 ]]; } || (( SECONDS - start >= wait_s )); then break; fi
  say "밀린 것이 빠지는 중 — 빠진 주문 $n_missing · outbox(미발행/격리) $v7_detail"
  sleep 10
done
waited=$((SECONDS - start))

# --- V2 라우트 stop 주문 중복 -------------------------------------------------------------------------------
# 주문 하나는 한 웨이브에, 웨이브 하나는 계획 하나에 — 주문은 route_stop_orders 에 한 번만 있어야 한다(재배정은 옮긴다).
v2=$(sqlv dispatch "SELECT count(*) FROM (SELECT order_id FROM route_stop_orders GROUP BY order_id HAVING count(*) > 1) d")

# --- V3 processed_events 중복 — 구조상 0, 그래서 제약의 존재를 본다 --------------------------------------------
# 카운트는 PK 가 있는 한 언제나 0 이라 아무것도 보지 않는다. 보는 것은 「구조상 0」이 사실인가 — PK (event_id, consumer).
v3_detail=""; v3_bad=0
for db in "${SERVICES_DB[@]}"; do
  pk=$(sqlv "$db" "SELECT string_agg(k.column_name, ',' ORDER BY k.ordinal_position)
                     FROM information_schema.table_constraints c
                     JOIN information_schema.key_column_usage k USING (constraint_schema, constraint_name)
                    WHERE c.table_name = 'processed_events' AND c.constraint_type = 'PRIMARY KEY'")
  [[ "$pk" == "event_id,consumer" ]] || v3_bad=1
  v3_detail+="$db ${pk:-없음} · "
done
v3_detail=${v3_detail% · }

# --- V4 감사 UNKNOWN — 관찰(기대값 없음) ---------------------------------------------------------------------
# 카오스 중 운영자 커맨드가 자연히 UNKNOWN 을 만들었는가. 만들지 않는다 — 생기면 7-3b 의 해소 경로가 닫는다.
v4=$(sqlv ops "SELECT count(*) FROM audit_logs WHERE result = 'UNKNOWN' AND created_at >= '$t0'")
v4_actions=$(sqlv ops "SELECT coalesce(string_agg(action || ' ' || n, ', '), '') FROM (SELECT action, count(*) n FROM audit_logs WHERE created_at >= '$t0' GROUP BY action ORDER BY action) a")

# --- V5 DLQ 증가 --------------------------------------------------------------------------------------------
dlq_now=$(dlq_end_sum)
v5=""; [[ -n "$dlq_now" ]] && v5=$((dlq_now - dlq))

# --- V6 rm_orders 걸린 행 — dawnline_rm_orders_stuck 과 같은 문장(JdbcReadModelRetention.COUNT_STUCK_SQL) --------
# 보존 기간(§7.1 보존 표 rm_orders 90일)을 넘긴 비종결 행. 카오스 한 번이 늘리는 값이 아니라 추세다 — 늘었는지를 본다.
v6=$(sqlv ops "SELECT count(*) FROM rm_orders o WHERE o.updated_at < now() - interval '90 days'
                 AND (o.order_status IS NULL OR o.order_status NOT IN ('CANCELLED', 'UNSERVICEABLE'))
                 AND o.delivery_outcome IS NULL")

# --- 표 -----------------------------------------------------------------------------------------------------
# 판정은 표를 그리기 전에 모은다 — mark 는 $(…) 안에서 불리므로(서브셸) 거기서 세운 변수는 밖에 남지 않는다. 처음 판이 그래서
# ✗ 가 있어도 종료 코드 0 이었다(2026-09-25 chaos-db 첫 실행).
mark() { if [[ "$1" == ok ]]; then echo "✅"; elif [[ "$1" == obs ]]; then echo "관찰"; else echo "✗"; fi; }
ok_if() { [[ "$1" == "$2" ]] && echo ok || echo bad; }

if [[ -n "$expect_orders" ]]; then premise_ok=$([[ "$n_order" == "$expect_orders" ]] && echo 1 || echo 0); premise="주문 ${expect_orders}"
else premise_ok=$(( n_order > 0 ? 1 : 0 )); premise="주문 ≥ 1"; fi
if [[ "$premise_ok" == 1 && "$n_missing" == 0 ]]; then r1=ok; else r1=bad; fi
r2=$( [[ -n "$v2" ]] && ok_if "$v2" 0 || echo bad)
r3=$( [[ "$v3_bad" == 0 ]] && echo ok || echo bad)
r4=$( [[ -n "$v4" ]] && echo obs || echo bad)
if [[ -z "$v5" ]]; then r5=bad; elif [[ -n "$expect_dlq" ]]; then r5=$(ok_if "$v5" "$expect_dlq"); else r5=obs; fi
r6=$( [[ -n "$v6" ]] && echo obs || echo bad)
r7=$( [[ "$v7_bad" == 0 ]] && echo ok || echo bad)
reason_table=""; r1_reasons=ok
while IFS='|' read -r r k n e v; do
  [[ "$v" == ok ]] || r1_reasons=bad
  reason_table+="| V1·사유 | 배차 불가 \`$r\` — $k | $n | $e | $(mark "$v") |"$'\n'
done < "$tmp/reason_rows"

table=$(cat <<TABLE
### 검증 표 — ${kind}

기준 T0 \`${t0}\` · 잰 시각 \`$(date -u +%Y-%m-%dT%H:%M:%SZ)\` · 밀린 것을 기다린 시간 ${waited}s

| # | 검사 | 값 | 기대 | 판정 |
|---|---|---|---|---|
| V1 | 유실 — 주문 = 후보 + 취소 + 배차 불가 | ${n_order} = ${n_cand} + ${n_cancel} + ${n_unsv} · **빠진 주문 ${n_missing}** | 전제 ${premise} · 빠진 주문 0 | $(mark "$r1") |
${reason_table%$'\n'}
| V2 | 라우트 stop 주문 중복 | ${v2:-모름} | 0 | $(mark "$r2") |
| V3 | processed_events 중복 — 구조상 0, PK 를 본다 | ${v3_detail} | 다섯 DB 전부 \`event_id,consumer\` | $(mark "$r3") |
| V4 | 감사 \`UNKNOWN\` (T0 이후) | ${v4:-모름} (커맨드: ${v4_actions:-없음}) | 관찰 — 만들지 않는다 | $(mark "$r4") |
| V5 | DLQ 증가 (\`*.dlq\` 끝 오프셋 합) | ${v5:-모름} | ${expect_dlq:-관찰} | $(mark "$r5") |
| V6 | \`rm_orders\` 걸린 행 (보존 90일을 넘긴 비종결) | ${v6:-모름} | 관찰 — 추세 | $(mark "$r6") |
| V7 | outbox 미발행 / 격리 | ${v7_detail} | 전부 0/0 | $(mark "$r7") |
TABLE
)
fail=0
for r in "$r1" "$r1_reasons" "$r2" "$r3" "$r4" "$r5" "$r6" "$r7"; do [[ "$r" == bad ]] && fail=1; done
echo "$table"
[[ -n "$out" ]] && { echo "$table" >> "$out"; echo >> "$out"; }
if [[ "$n_missing" != 0 && -s "$tmp/missing" ]]; then
  echo; echo "빠진 주문(앞 10):"; head -10 "$tmp/missing"
fi
exit $fail
