#!/usr/bin/env bash
# =============================================================================
# make obs-check — 떠 있는 스택의 관측성이 설계서대로 섰는가 (DESIGN.md §9.4, ADR-060)
#
# Compose 스모크가 `make demo` 뒤에 돌린다. 넷을 본다:
#   1. Prometheus 가 규칙 파일의 알림 전부를 적재했고 평가에 실패한 규칙이 없다
#   2. Grafana 가 커밋된 대시보드 JSON 전부를 프로비저닝했다(uid 집합이 같다)
#   3. 패널 · 규칙이 쓰는 이름 가운데 **표(§9.1)가 없는 이름**(kafka_* · hikaricp_* · jvm_* · http_server_requests_*)과
#      **버킷**(*_bucket)이 실제로 긁히고 있다
#   4. 트레이스 (§9.2, ADR-062 결정 5) — 데모의 웨이브 하나로 TraceQL { span.dawnline.wave_id = "…" } 를 Tempo 에 묻고,
#      결과 트레이스들의 service.name 합집합에 코어 넷(order · fulfillment · dispatch · tracking)이 있다.
#      주문과 계획은 두 트레이스이고 이 질의가 둘을 함께 돌려준다 — 「이 웨이브의 일이 네 서비스를 지나 한 질의로
#      찾아진다」가 §9.2 의 요구 그대로다. 서비스 이름으로 검색해 트레이스를 하나씩 여는 것은 그 뜻이 아니다.
#
# 3 이 따로 있는 이유: dawnline_* 이름은 단위 테스트가 §9.1 과 대조하지만(DashboardsConsistencyTest), 플랫폼 지표는
# 대조할 표가 없다 — 이름이 틀리면 패널이 조용히 비어 있다. 버킷도 같다: 계획 시간의 버킷은 속성 파일의 키가 미터 이름과
# 달라 한 번도 생기지 않았고(ADR-060 맥락 1), 그 사실은 긁어 봐야 보인다.
# =============================================================================
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

ENV_FILE="deploy/compose/.env"
set -a; . "$ENV_FILE"; set +a

OBS_TIMEOUT="${OBS_TIMEOUT:-90}"
COMPOSE=(docker compose -f deploy/compose/docker-compose.yml --env-file "$ENV_FILE")

# 4 의 웨이브 — 데모가 계획까지 낸(PUBLISHED) 가장 최근 웨이브. 계획이 발행돼야 tracking(route.assigned)과
# order(order.dispatched)가 계획 트레이스에 들어온다.
WAVE="$("${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" postgres \
  psql -qtAX -U "$POSTGRES_SUPERUSER" -d dawnline_dispatch \
  -c "SELECT wave_id FROM route_plans WHERE status = 'PUBLISHED' ORDER BY finished_at DESC LIMIT 1")"

PROM="http://localhost:${PROMETHEUS_PORT}" GRAF="http://localhost:${GRAFANA_PORT}" TEMPO="http://localhost:${TEMPO_HTTP_PORT}" \
  WAVE="$WAVE" OBS_TIMEOUT="$OBS_TIMEOUT" python3 - <<'PY'
import glob, json, os, re, sys, time, urllib.parse, urllib.request

PROM, GRAF, TIMEOUT = os.environ["PROM"], os.environ["GRAF"], int(os.environ["OBS_TIMEOUT"])
TEMPO, WAVE = os.environ["TEMPO"], os.environ["WAVE"].strip()
RULES = "deploy/compose/prometheus/rules/dawnline-alerts.yml"
BOARDS = sorted(glob.glob("deploy/compose/grafana/dashboards/*.json"))

def get(url):
    with urllib.request.urlopen(url, timeout=10) as response:
        return json.load(response)

def until(what, check):
    deadline = time.time() + TIMEOUT
    last = None
    while time.time() < deadline:
        try:
            ok, last = check()
            if ok:
                print(f"  ✓ {what}")
                return
        except Exception as e:  # 아직 뜨는 중
            last = repr(e)
        time.sleep(3)
    print(f"  ✗ {what} — {last}")
    sys.exit(1)

print("관측성 확인 (DESIGN.md §9.4)")

expected_alerts = set(re.findall(r"(?m)^\s+- alert: (\S+)", open(RULES, encoding="utf-8").read()))
def rules_loaded():
    groups = get(PROM + "/api/v1/rules")["data"]["groups"]
    rules = [r for g in groups for r in g["rules"] if r["type"] == "alerting"]
    names = {r["name"] for r in rules}
    broken = [r["name"] for r in rules if r.get("health") not in ("ok", "unknown")]
    return names == expected_alerts and not broken, f"적재 {sorted(names ^ expected_alerts)} 평가 실패 {broken}"
until(f"Prometheus 가 알림 {len(expected_alerts)}개를 적재했고 평가 실패가 없다", rules_loaded)

expected_uids = {json.load(open(f, encoding="utf-8"))["uid"] for f in BOARDS}
def boards_provisioned():
    uids = {d["uid"] for d in get(GRAF + "/api/search?type=dash-db") if d.get("uid", "").startswith("dawnline-")}
    return uids == expected_uids, f"등록 {sorted(uids)} 기대 {sorted(expected_uids)}"
until(f"Grafana 가 대시보드 {len(expected_uids)}개를 프로비저닝했다", boards_provisioned)

exprs = [open(RULES, encoding="utf-8").read()]
for f in BOARDS:
    board = json.load(open(f, encoding="utf-8"))
    for panel in board["panels"] + [p for row in board["panels"] for p in row.get("panels", [])]:
        exprs += [t.get("expr", "") for t in panel.get("targets", [])]
text = "\n".join(exprs)
platform = set(re.findall(r"\b((?:kafka|hikaricp|jvm|http_server_requests)_[a-z0-9_]+)", text))
buckets = set(re.findall(r"\b([a-z0-9_]+_bucket)\b", text))
for name in sorted(platform | buckets):
    def present(name=name):
        query = urllib.parse.quote(f'count({{__name__="{name}"}})')
        result = get(PROM + "/api/v1/query?query=" + query)["data"]["result"]
        return bool(result), "긁히지 않는다"
    until(f"긁히고 있다: {name}", present)

# 4. 트레이스 — 주문 트레이스의 끝(후보 적재)과 계획 트레이스의 시작(계획)이 같은 속성을 단다(§9.3).
CORE = {"order-service", "fulfillment-service", "dispatch-service", "tracking-service"}
if not WAVE:
    print("  ✗ 트레이스 — 데모가 발행한 계획(route_plans.status = 'PUBLISHED')이 없다")
    sys.exit(1)

def services_of(trace_id):
    trace = get(f"{TEMPO}/api/traces/{trace_id}")
    batches = trace.get("batches") or trace.get("resourceSpans") or []
    names = set()
    for batch in batches:
        for attribute in batch.get("resource", {}).get("attributes", []):
            if attribute["key"] == "service.name":
                names.add(attribute["value"].get("stringValue"))
    return names

# 검색은 limit 에서 자른다 — 웨이브 하나에 주문 트레이스가 수백이고 계획 트레이스는 하나다. 잘린 결과는 계획 트레이스를
# 빼고도 돌아올 수 있으므로(처음 판은 limit 100 에 데모 웨이브의 트레이스가 177개였다) 결과가 한도에 닿으면 답이 아니다.
LIMIT = 1000

def one_query_finds_the_core():
    query = '{ span.dawnline.wave_id = "%s" }' % WAVE
    now = int(time.time())
    params = urllib.parse.urlencode({"q": query, "limit": LIMIT, "start": now - 3600, "end": now + 60})
    traces = get(f"{TEMPO}/api/search?{params}").get("traces", [])
    if len(traces) >= LIMIT:
        print(f"  ✗ 트레이스 — 결과가 한도({LIMIT})에 닿았다. 잘린 결과로는 합집합을 말할 수 없다")
        sys.exit(1)
    found = set()
    for trace in traces:
        found |= services_of(trace["traceID"])
    return CORE <= found, f"트레이스 {len(traces)}개 · 서비스 {sorted(found)} · 빠진 것 {sorted(CORE - found)}"
until(f"TraceQL 한 줄(웨이브 {WAVE})의 트레이스들이 코어 넷을 지난다", one_query_finds_the_core)
PY
