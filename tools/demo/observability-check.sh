#!/usr/bin/env bash
# =============================================================================
# make obs-check — 떠 있는 스택의 관측성이 설계서대로 섰는가 (DESIGN.md §9.4, ADR-060)
#
# Compose 스모크가 `make demo` 뒤에 돌린다. 다섯을 본다:
#   1. Prometheus 가 **지금 파일에 있는 규칙**을 적재했고 평가에 실패한 규칙이 없다 — 이름이 아니라 내용을 대조한다.
#      그룹마다 규칙의 (종류 · 이름 · 식 · for · keep_firing_for · 라벨 · 주석)을 양쪽에서 같은 꼴로 만들어 해시를 견준다.
#      식은 Prometheus 의 /api/v1/format_query 로 양쪽 다 정규화한다 — 적재된 식은 파서가 다시 쓴 문자열이라 파일의 글자와 다르다.
#      이름만 보던 처음 판은 **옛 규칙이 적재된 것처럼 보였다**(2026-09-25, 7-3① 2차 chaos-db): 떠 있던 Prometheus 가 바뀐
#      규칙 파일을 다시 읽지 않았고(`make up` 은 설정만 바뀐 컨테이너를 다시 만들지 않는다), 알림 이름은 그대로라 초록이었다.
#      그 10분 동안 `DawnlineConsumerLag` 는 옛 식으로 판정됐다(§13 「꺼 둔 검증은 실패하지 않는다」).
#   2. Grafana 가 커밋된 대시보드 JSON 전부를 프로비저닝했다(uid 집합이 같다)
#   3. 패널 · 규칙이 쓰는 이름 가운데 **표(§9.1)가 없는 이름**(kafka_* · hikaricp_* · jvm_* · http_server_requests_*)과
#      **버킷**(*_bucket)이 실제로 긁히고 있다
#   4. 트레이스 (§9.2, ADR-062 결정 5) — 데모의 웨이브 하나로 TraceQL { span.dawnline.wave_id = "…" } 를 Tempo 에 묻고,
#      결과 트레이스들의 service.name 합집합에 코어 넷(order · fulfillment · dispatch · tracking)이 있다.
#      주문과 계획은 두 트레이스이고 이 질의가 둘을 함께 돌려준다 — 「이 웨이브의 일이 네 서비스를 지나 한 질의로
#      찾아진다」가 §9.2 의 요구 그대로다. 서비스 이름으로 검색해 트레이스를 하나씩 여는 것은 그 뜻이 아니다.
#   5. 서비스 그래프 (§9.2) — Tempo metrics_generator 가 remote-write 한 traces_service_graph_request_total 에
#      코어 넷 사이의 간선(client → server)이 하나라도 있다. 4 와 독립된 둘째 증거다: 전파가 서비스 경계를 넘는다는 것을
#      트레이스 검색이 아니라 **메트릭**이 말한다. 코어 사이에는 동기 호출이 없으므로(불변 규칙 4) 그 간선은 Kafka 의
#      PRODUCER → CONSUMER 쌍뿐이다 — 발행 스팬의 id 가 소비 스팬의 부모일 때만 생긴다.
#      「간선의 양 끝이 코어 넷을 덮는다」로 조였다가 되돌렸다(2026-09-25, #72 의 CI): 소비자가 여럿인 토픽에서
#      발행 스팬 하나는 한 소비자와만 짝지어지는 것으로 보이고(추정), dispatch→tracking 은 route.assigned 를 함께 받는
#      ops-api 와의 경합에서 이긴 때만 나온다. 4 가 같은 실행에서 tracking 을 봤다 — 결손이 아니라 짝짓기의 우연이다.
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
import glob, hashlib, json, os, re, sys, time, urllib.parse, urllib.request
try:
    import yaml
except ImportError:
    print("  ✗ 규칙 대조에 PyYAML 이 필요하다 — python3 -m pip install pyyaml")
    sys.exit(1)

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

# 1. 규칙 — 파일과 적재된 것을 같은 꼴로 만들어 그룹마다 해시를 견준다.
UNITS = {"ms": 0.001, "s": 1, "m": 60, "h": 3600, "d": 86400, "w": 604800, "y": 31536000}
def seconds(value):
    if value in (None, 0, "0", ""):
        return 0
    if isinstance(value, (int, float)):
        return float(value)
    parts = re.findall(r"(\d+)(ms|s|m|h|d|w|y)", value)
    if "".join(n + u for n, u in parts) != value:
        raise ValueError(f"기간을 읽지 못했다: {value}")
    return float(sum(int(n) * UNITS[u] for n, u in parts))

formatted = {}
def fmt(expr):
    if expr not in formatted:
        formatted[expr] = get(PROM + "/api/v1/format_query?query=" + urllib.parse.quote(expr))["data"]
    return formatted[expr]

def canonical(kind, name, expr, for_, keep, labels, annotations):
    return {"kind": kind, "name": name, "expr": fmt(expr), "for": seconds(for_), "keep_firing_for": seconds(keep),
            "labels": {k: str(v) for k, v in (labels or {}).items()},
            "annotations": {k: str(v) for k, v in (annotations or {}).items()}}

def from_file():
    doc = yaml.safe_load(open(RULES, encoding="utf-8"))
    return {g["name"]: [canonical("alert" if "alert" in r else "record", r.get("alert") or r.get("record"), r["expr"],
                                  r.get("for"), r.get("keep_firing_for"), r.get("labels"), r.get("annotations"))
                        for r in g["rules"]] for g in doc["groups"]}

def from_prometheus(groups):
    return {g["name"]: [canonical("alert" if r["type"] == "alerting" else "record", r["name"], r["query"],
                                  r.get("duration"), r.get("keepFiringFor"), r.get("labels"), r.get("annotations"))
                        for r in g["rules"]] for g in groups}

def digest(rules):
    return hashlib.sha256(json.dumps(rules, sort_keys=True, ensure_ascii=False).encode()).hexdigest()[:12]

expected = from_file()
n_alerts = sum(1 for rules in expected.values() for r in rules if r["kind"] == "alert")
def rules_loaded():
    groups = get(PROM + "/api/v1/rules")["data"]["groups"]
    loaded = from_prometheus(groups)
    broken = [r["name"] for g in groups for r in g["rules"] if r.get("health") not in ("ok", "unknown")]
    differ = []
    # 재적재가 실패하면 옛 규칙이 그대로 돈다 — 단일 파일 바인드 마운트는 git checkout 이 파일을 새로 쓰면 지워진 inode 를 붙든다
    # (2026-09-26 관측: 컨테이너 안 prometheus.yml 의 링크 수 0, 재적재 「no such file」). 컨테이너를 다시 만들어야 풀린다.
    if not get(PROM + "/api/v1/status/runtimeinfo")["data"].get("reloadConfigSuccess", False):
        differ.append("마지막 재적재가 실패했다 — 설정 파일이 컨테이너에서 보이지 않으면 dc up -d --force-recreate prometheus")
    for name in sorted(set(expected) | set(loaded)):
        want, got = expected.get(name), loaded.get(name)
        if want is None or got is None:
            differ.append(f"그룹 {name}: {'파일에 없다' if want is None else '적재되지 않았다'}")
        elif digest(want) != digest(got):
            by_name = {r["name"]: r for r in got}
            changed = [r["name"] for r in want if by_name.get(r["name"]) != r] + \
                      [r for r in by_name if r not in {w["name"] for w in want}]
            differ.append(f"그룹 {name}: 파일 {digest(want)} ≠ 적재 {digest(got)} — 다른 규칙 {changed}")
    summary = " · ".join(f"{n} {digest(r)}" for n, r in sorted(expected.items()))
    if not differ and not broken:
        print(f"    (그룹 해시 {summary})")
    hint = " — 적재가 옛 것이면 curl -X POST $PROM/-/reload" if differ else ""
    return not differ and not broken, f"{differ} 평가 실패 {broken}{hint}"
until(f"Prometheus 가 파일의 규칙을 그대로 적재했다(알림 {n_alerts}개 · 내용 해시) · 평가 실패가 없다", rules_loaded)

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

# 5. 서비스 그래프 — Prometheus 의 즉시 질의는 한도가 없다(limit 파라미터를 주지 않는다). 4 처럼 잘린 결과를 읽을 일이 없다.
def core_edge_is_scraped():
    query = urllib.parse.quote("sum by (client, server, connection_type) (traces_service_graph_request_total)")
    result = get(PROM + "/api/v1/query?query=" + query)["data"]["result"]
    edges = sorted((r["metric"].get("client", ""), r["metric"].get("server", ""),
                    r["metric"].get("connection_type", "")) for r in result)
    core = [e for e in edges if e[0] in CORE and e[1] in CORE and e[0] != e[1]]
    shown = ", ".join(f"{c}→{s}" + (f"({t})" if t else "") for c, s, t in edges) or "없음"
    return bool(core), f"코어 사이 간선 없음 · 간선 {shown}"
until("서비스 그래프에 코어 사이 간선이 긁힌다", core_edge_is_scraped)
PY
