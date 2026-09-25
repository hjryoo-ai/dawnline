#!/usr/bin/env bash
# =============================================================================
# make obs-check — 떠 있는 스택의 관측성이 설계서대로 섰는가 (DESIGN.md §9.4, ADR-060)
#
# Compose 스모크가 `make demo` 뒤에 돌린다. 셋을 본다:
#   1. Prometheus 가 규칙 파일의 알림 전부를 적재했고 평가에 실패한 규칙이 없다
#   2. Grafana 가 커밋된 대시보드 JSON 전부를 프로비저닝했다(uid 집합이 같다)
#   3. 패널 · 규칙이 쓰는 이름 가운데 **표(§9.1)가 없는 이름**(kafka_* · hikaricp_* · jvm_* · http_server_requests_*)과
#      **버킷**(*_bucket)이 실제로 긁히고 있다
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

PROM="http://localhost:${PROMETHEUS_PORT}" GRAF="http://localhost:${GRAFANA_PORT}" OBS_TIMEOUT="$OBS_TIMEOUT" python3 - <<'PY'
import glob, json, os, re, sys, time, urllib.parse, urllib.request

PROM, GRAF, TIMEOUT = os.environ["PROM"], os.environ["GRAF"], int(os.environ["OBS_TIMEOUT"])
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
PY
