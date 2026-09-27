#!/usr/bin/env bash
# =============================================================================
# make peak-facts — 창 시나리오 한 판의 두 줄을 두 DB 의 사실로 (ADR-067 후속 「기사는 반영 뒤에 출발한다」)
#
#   1. 반영 — dispatch 가 route.assigned 를 발행한 시각(outbox_events.published_at)과 tracking 이 그 개정을 적용한 시각
#      (route_revisions.applied_at). 발행 → 반영 지연의 분위수와, 계획 버스트의 처리량(라우트/초 · shipment/초).
#   2. 스캔 — tracking 의 스캔 원장(shipment_events)의 건수와 사건 시각(occurred_at)의 폭. 벽시계 쪽 폭은 여기 없다 —
#      sim-runner 로그의 「기사 출발」과 「기사 시뮬레이션 완료」 사이다. 둘의 비가 달성된 배속이다.
#
# 지표를 새로 만들지 않는다 — 사실이 있는데 지표를 만드는 것은 둘째 출처다. 경로는 make psql 과 같다(compose 의 postgres 에
# 슈퍼유저로). 범위는 **스택 전체**다: 측정은 make sim-reset 으로 시작하므로(ADR-066 후속) 이 볼륨의 행은 전부 이번 실행의 것이다.
# 조인은 tracking 세션의 임시 테이블에서 한다 — 두 DB 는 서로를 모르고(불변규칙 3), 모르는 채로 두는 것은 이 도구도 같다.
# =============================================================================
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ENV_FILE="deploy/compose/.env"
set -a; . "$ENV_FILE"; set +a
export COMPOSE_PROJECT_NAME=dawnline-sim

if ! docker ps --filter "label=com.docker.compose.project=dawnline-sim" --format '{{.Names}}' | grep -q .; then
  echo "시뮬레이션 스택(프로젝트 dawnline-sim)이 떠 있지 않다 — 사실은 그 볼륨에 있다." >&2
  exit 2
fi

COMPOSE=(docker compose -f deploy/compose/docker-compose.yml --env-file "$ENV_FILE")
psql_in() {
  "${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" postgres \
    psql -v ON_ERROR_STOP=1 -X -q -U "$POSTGRES_SUPERUSER" -d "$1" "${@:2}"
}

# 발행된 route.assigned — 개정 하나가 한 행, 계획의 약속창 시작(KST)을 무리 이름으로 붙인다: 00:00 은 DAWN, 08:00 은 NEXT_DAY 다.
# 같은 컷오프(00:00)에 닫힌 두 티어의 라우트가 같은 버스트로 tracking 에 닿으므로 둘 다 이번 실행이다 — 나눠서 보이게만 한다.
psql_in dawnline_dispatch -At -c "COPY (
  SELECT o.payload->>'routeId', (o.payload->>'revision')::int, o.published_at,
         (SELECT count(*) FROM jsonb_array_elements(o.payload->'stops') s, jsonb_array_elements(s->'orderIds')),
         to_char(w.promised_from AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI')
    FROM outbox_events o
    JOIN route_plans p ON p.id = (o.payload->>'planId')::uuid
    JOIN LATERAL (SELECT min(c.promised_start) AS promised_from FROM dispatch_candidates c WHERE c.wave_id = p.wave_id) w ON true
   WHERE o.event_type = 'route.assigned') TO STDOUT WITH (FORMAT csv)" |
psql_in dawnline_tracking -P footer=off \
  -c "CREATE TEMP TABLE pub (route_id uuid, revision int, published_at timestamptz, orders int, promised_from text)" \
  -c "\copy pub FROM STDIN WITH (FORMAT csv)" \
  -c "CREATE TEMP VIEW fact AS
        SELECT p.*, r.revision AS applied_revision, r.applied_at,
               CASE WHEN p.published_at IS NULL THEN '미발행'
                    WHEN r.route_id IS NULL THEN '라우트를 모른다'
                    WHEN r.revision = p.revision THEN '반영'
                    WHEN r.revision > p.revision THEN '뒤 개정이 덮음'
                    ELSE '아직 앞 개정' END AS state
          FROM pub p LEFT JOIN route_revisions r USING (route_id)" \
  -c "\echo '## 반영 — dispatch outbox.published_at → tracking route_revisions.applied_at'" \
  -c "\echo 'route_revisions 는 라우트마다 마지막 개정만 들고 있다(PK route_id) — 뒤 개정이 덮은 개정의 적용 시각은 남지 않는다.'" \
  -c "SELECT promised_from AS 약속창_시작, state AS 상태, count(*) AS 개정 FROM fact GROUP BY 1, 2 ORDER BY 1, 2" \
  -c "SELECT promised_from AS 약속창_시작, revision = 1 AS 최초_확정, count(*) AS 개정,
             round(percentile_cont(0.5) WITHIN GROUP (ORDER BY lag)::numeric, 1) AS p50_초,
             round(percentile_cont(0.9) WITHIN GROUP (ORDER BY lag)::numeric, 1) AS p90_초,
             round(percentile_cont(0.99) WITHIN GROUP (ORDER BY lag)::numeric, 1) AS p99_초,
             round(max(lag)::numeric, 1) AS 최대_초
        FROM (SELECT *, extract(epoch FROM applied_at - published_at) AS lag FROM fact WHERE state = '반영') f
       GROUP BY 1, 2 ORDER BY 1, 2 DESC" \
  -c "\echo '## 반영 처리량 — 무리마다 최초 확정(개정 1)의 첫 발행부터 마지막 반영까지. 덮인 개정 1 은 적용 시각이 없어 빠진다'" \
  -c "SELECT promised_from AS 약속창_시작, count(*) AS 라우트, sum(orders) AS shipment,
             round(extract(epoch FROM max(applied_at) - min(published_at))::numeric, 1) AS 폭_초,
             round((count(*) / nullif(extract(epoch FROM max(applied_at) - min(published_at)), 0))::numeric, 1) AS 라우트_초당,
             round((sum(orders) / nullif(extract(epoch FROM max(applied_at) - min(published_at)), 0))::numeric, 0) AS shipment_초당
        FROM fact WHERE state = '반영' AND revision = 1 GROUP BY 1 ORDER BY 1" \
  -c "\echo '## 스캔 — 원장(shipment_events)은 주문마다 한 행이다: 스캔 한 번이 그 stop 의 주문 수만큼, 출발은 라우트의 주문 전부만큼.'" \
  -c "\echo 'HTTP 스캔 수는 sim-runner 리포트의 「보낸 스캔」이고, 여기서 보는 것은 사건 시각(시뮬레이션 시각)의 폭이다.'" \
  -c "SELECT r.promised_from AS 약속창_시작, count(*) FILTER (WHERE e.type = 'DEPARTED_CAMP') AS 출발,
             count(*) FILTER (WHERE e.type = 'ARRIVED') AS 도착, count(*) FILTER (WHERE e.type = 'COMPLETED') AS 완료,
             count(*) FILTER (WHERE e.type = 'FAILED') AS 실패,
             to_char(min(e.occurred_at) AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI') AS 첫_사건,
             to_char(max(e.occurred_at) AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI') AS 끝_사건,
             round(extract(epoch FROM max(e.occurred_at) - min(e.occurred_at))::numeric / 3600, 2) AS 폭_시간
        FROM shipment_events e JOIN (SELECT DISTINCT route_id, promised_from FROM pub) r USING (route_id)
       GROUP BY 1 ORDER BY 1"
