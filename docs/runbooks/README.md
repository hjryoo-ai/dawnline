# 런북 — 알림 15 × 대응, 알림 밖 절차 셋

| 항목 | 내용 |
|---|---|
| 대상 | `docs/DESIGN.md` §9.4 의 알림 15개 전부 · 알림이 없는 사건 셋 |
| 관련 설계 | §8.4(장애 모드 표) · §9.4(알림 규칙) · §9.5(런북 목록) |
| 대조 | `RunbooksConsistencyTest`(`libs/observability`) — 아래 1 의 알림 집합이 규칙 파일(`deploy/compose/prometheus/rules/dawnline-alerts.yml`)과 같고, 「절차」 칸이 규칙의 `runbook` 주석과 같고, 「먼저 본다」 칸과 모든 절차의 첫 줄이 **메트릭 · 로그 · SQL** 중 하나로 시작한다 |

**읽는 법.** 알림은 증상이고, 「먼저 본다」는 **원인을 가르는 첫 질문**이다 — 그 자리가 메트릭인지 로그인지 SQL 인지가 칸의
첫 단어다. 거기서 갈래가 정해지면 「갈래 · 대응」을 따른다. 「절차」 칸이 RB 면 그 문서가 절차이고, `—` 면 **이 행이 절차 전부다**
(그 알림의 `runbook` 주석도 없다 — 대조 검사가 둘을 맞춘다).

**공통 준비** — 저장소 루트에서 한 번. 아래 모든 절차가 이 넷을 쓴다.

```bash
set -a; . deploy/compose/.env; set +a
dc()   { docker compose -f deploy/compose/docker-compose.yml --env-file deploy/compose/.env --profile app --profile obs "$@"; }
prom() { curl -s "http://localhost:$PROMETHEUS_PORT/api/v1/query" --data-urlencode "query=$1" | jq -c '.data.result[] | [.metric, .value[1]]'; }
logs() { dc logs --no-log-prefix --since "${2:-30m}" "$1"; }      # logs dispatch-service 2h | grep '정체된 계획'
sql()  { dc exec -T -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" postgres psql -U "$POSTGRES_SUPERUSER" -d "dawnline_$1" -c "$2"; }
```

- **메트릭** — `prom '<PromQL>'`, 또는 Grafana Explore(`http://localhost:$GRAFANA_PORT`). 모든 시계열에 `service` 라벨이 있다.
- **로그** — 구조화 JSON 한 줄이 한 사건이다. 메시지 문자열로 거른다(아래 표의 따옴표 안이 코드의 그 문자열이다). `make logs` 는 따라가기(`-f`)라
  `grep` 이 끝나지 않는다 — 위의 `logs` 를 쓴다.
- **SQL** — `sql <order|fulfillment|dispatch|tracking|ops> '<문장>'`. 관리자 계정이다 — **읽기만 한다.** 쓰는 문장은 각 절차가 따로
  적고, 그때는 그 서비스의 계정으로 간다(RB-05 1.2).

---

## 1. 알림 15 × 대응

| 알림 | 먼저 본다 | 갈래 · 대응 | 절차 |
|---|---|---|---|
| `DawnlineOutboxLag` | **메트릭** `dawnline_outbox_leader{service="<알림의 service>"}` — 인스턴스마다 `1` 리더 · `0` 팔로워 · `-1` 판정 불가 | `1` 이 있다 → 리더가 발행하는데 브로커가 받지 않는다: RB-01 §1 · `-1` → DB 세션을 잃었다 — 발행할 행도 못 읽는다: RB-02 · 전부 `0` → 락을 **다른 세션**이 쥐었다: RB-01 §1.3 | RB-01 |
| `DawnlineOutboxFailed` | **SQL** `sql <service> "SELECT event_type, count(*), min(failed_at) FROM outbox_events WHERE failed_at IS NOT NULL GROUP BY 1"` — 원인이 하나인가 여럿인가 | 뒤의 행은 이미 흐르고 있다 — 급한 불은 꺼져 있다. 유형마다 격리 로그(「격리합니다」)의 예외로 원인을 찾고, 고친 뒤 **한 행씩** 재큐한다. 같은 행 목록은 코어의 격리 목록 엔드포인트도 준다(payload 없이) | RB-05 |
| `DawnlineDlqNew` | **메트릭** `sum by (consumer, eventType) (dawnline_event_processed_total{outcome="dlq"})` — 어느 그룹이 무엇을 | 그룹 하나 · 여러 타입 → 그 소비자나 그 DB 가 죽었다(**DB 장애로는 들어오지 않는다** — 일시적 실패는 끝없이 재시도한다, 7-3 · RB-02 §3. DLQ 에 온 것은 결정적 실패다) · 타입 하나 · 여러 그룹 → 계약 · 스키마 문제 · 비즈니스 규칙 위반으로 보이면 소비자 버그다(`rejected` 로 가야 했다). **소비자를 먼저 고치고** 재처리한다 | RB-05 |
| `DawnlineConsumerLag` | **메트릭** 그 서비스의 처리율 `sum by (consumer, outcome) (rate(dawnline_event_processed_total{service="<알림의 consumergroup>"}[5m]))` — 알림의 랙은 브로커 기준(kafka-exporter)이고 그룹 이름이 서비스다 | 처리율 0 → 멈췄다: DB(RB-02) · 리밸런스 반복 · 재시도 중인 레코드(일시적 실패면 풀릴 때까지 멈춘다 — `dawnline_event_retry_age_seconds`, 다음 행) · 처리율이 있는데 랙이 는다 → 유입 > 처리, 피크다(RB-06). dispatch 의 랙은 자동 FAST 를 부른다(`dawnline_plan_degraded_total{reason="LAG"}` — 설계된 동작) | RB-01 |
| `DawnlineConsumerRetryStuck` | **메트릭** `sum by (reason) (increase(dawnline_event_retry_total{service="<service>"}[10m]))` — 무엇 때문에 멈췄나(경계표의 행, ADR-015 후속 정정) | **일시적 실패가 30분 넘게 이어지면 그건 장애가 아니라 설정이다.** `db_connection` · `db_resource` → 그 서비스의 DB 자격 증명 · 계정 · 연결 설정(RB-02 §2) · `redis` → RB-03 · `db_integrity` 가 반복된다 → 결정적일 가능성이 높다 — 데이터를 고치거나 그 레코드를 격리한다(사람이 거는 격리 경로는 아직 없다 — ADR-053 재검토) · `other` → 판정되지 않은 예외다 — 로그의 예외로 코드 결함을 찾는다. **그 파티션의 뒤는 전부 기다리고 있다** — 순서는 지켜지고, 원인이 풀리면 스스로 따라온다 | RB-02 |
| `DawnlinePlanDurationP95` | **메트릭** `sum by (mode, termination) (increase(dawnline_plan_duration_seconds_count[1h]))` — 잘린 계획(`termination="deadline"`)의 몫 | `deadline` 이 대부분 → 예산이 문다: 규모 · 차량 · 전략(RB-04 §2) · `converged` 인데 길다 → 기계가 느리다 — CPU 한도 · GC(RB-06 예열) · 알고리즘 밖의 시간은 여기 없다 — `dawnline_plan_persist_seconds` 가 따로 잰다 | RB-04 |
| `DawnlineOnTimeRatioLow` | **메트릭** 같은 캠프의 `dawnline_kpi_delivery{camp="<camp>"}`(completed · failed)와 `dawnline_delivery_on_time_ratio{camp="<camp>", basis="revised"}` | 실패가 늘었다 → 배송 현장이다: `dawnline_at_risk_total{camp}` · `dawnline_replan_total{outcome}`(`no-candidate` · `no-gain` 은 재계획이 도울 수 없었다) · 원 약속 기준만 낮고 개정 기준은 높다 → 약속이 밀렸다: `dawnline_promise_revised_total{camp, cause}` — `manual` 이면 조기 마감의 대가다(2.3), `scheduled` 면 컷오프 grace 로 흡수 못한 지연이다(컨슈머 랙 — RB-01 §2) · 둘 다 낮다 → 늦게 도착하고 있다: 라우트 진행(2.2)과 at-risk. **정시율은 원 약속이 기준이다**(§8.1) — 개정 기준이 높다는 것은 해명이 아니라 차이의 크기다 | — |
| `DawnlineKpiRefreshStale` | **로그** `logs ops-api 30m \| grep 'KPI 갱신 실패'` — 예외의 첫 줄 | 연결 · 풀 예외 → ops DB 다(RB-02) · 문장 타임아웃 → `rm_orders` 가 커졌다 — 걸린 행(`dawnline_rm_orders_stuck`)과 보존 정리(`dawnline_retention_last_success_age_seconds{table="rm_orders"}`)를 본다. **이 알림이 울리는 동안 정시율 알림은 울릴 수 없다**(값이 `NaN`) — 그동안의 정시율은 대시보드가 아니라 `sql ops "SELECT camp_id, sum(on_time_promised)::float / nullif(sum(delivered + failed), 0) FROM kpi_delivery_hourly WHERE bucket_hour > now() - interval '24 hours' GROUP BY 1"` 가 말한다. 재기동은 원인이 아니면 해법도 아니다 — 첫 성공에서 나이가 0 이 된다 | — |
| `DawnlineKpiPromiseUnknown` | **SQL** `sql ops "SELECT count(*) FILTER (WHERE promised_end_original IS NULL) AS no_placed, count(*) FILTER (WHERE camp_id IS NULL OR promised_end_revised IS NULL) AS no_planned FROM rm_orders WHERE delivery_outcome IS NOT NULL AND order_status <> 'CANCELLED' AND COALESCE(delivered_at, failed_at) >= date_trunc('hour', now(), 'UTC') - interval '23 hours' AND (camp_id IS NULL OR promised_end_original IS NULL OR promised_end_revised IS NULL)"` — 뷰(`kpi_delivery_hourly`)와 같은 조건이라 합이 게이지와 같다 | 결과는 왔는데 **어느 사실이 오지 않았나** — `no_planned` → `fulfillment.planned` 가 ops-api 에 닿지 않는다: fulfillment 의 outbox(`dawnline_outbox_lag_seconds{service="fulfillment-service"}`, RB-01 §1) 또는 ops-api 의 소비(`dawnline_event_processed_total{consumer="ops-api", eventType="fulfillment.planned"}` 의 `dlq`, RB-05 §2) · `no_placed` → `order.placed` 쪽, 같은 두 자리. 30분은 프로젝션 랙으로 설명되는 길이를 넘었다는 뜻이다 | — |
| `DawnlineRateLimitBypassed` | **메트릭** 다른 서비스의 Redis 폴백 — `sum by (service) (rate(dawnline_geo_lookups_total{outcome="bypassed"}[5m]))` · `rate(dawnline_at_risk_cooldown_bypassed_total[5m])` | 다른 서비스도 건너뛴다 → Redis 자체가 죽었다: RB-03 §1 · order-service 만 → Redis 는 살았고 50 ms 예산을 넘는다(느린 Redis · 네트워크): RB-03 §2. **그동안 무인증 API 에 남용 방지 수단이 없다**(§7.2) — 복구가 이 알림의 대응이고 대체 수단은 없다 | RB-03 |
| `DawnlineCancelTooLate` | **메트릭** `sum by (topic) (kafka_consumergroup_lag{consumergroup="order-service"} >= 0)` — order-service 가 `order.dispatched` · `delivery.status` 를 늦게 알았나 | 랙이 있다 → 주문 쪽이 배송을 모른 채 취소를 받았다 — 랙이 원인이다(RB-01 §2) · 랙이 없다 → 고객이 배송 직후에 눌렀다 — 경합 창의 끝이고 결함이 아니다. **어느 쪽이든 그 건은 사람이 닫는다**: 물건은 배송됐고 주문은 취소다(§6.10 — 자동 보상 없음). 건 목록은 ops-api `GET /api/v1/camps/{campId}/exceptions`(취소됐는데 배송된 주문, 창 없이 전부) · 주문 id 는 dispatch 로그 「배송이 끝난 뒤 도착한 취소입니다」 | — |
| `DawnlinePartitionsAheadLow` | **로그** `logs tracking-service 2h \| grep 'shipment_events 파티션 관리 실패'` — 예외 | 이 알림은 이틀의 여유를 두고 운다 — 0 이 되는 날 기사 스캔 INSERT 가 실패한다. 원인을 고치고 손으로 한 번 돌린다(RB-06 §1) | RB-06 |
| `DawnlineInternalTokenRejected` | **로그** 알림의 `service` 에서 `logs <service> 30m \| grep '내부 토큰 없이 들어온 운영자 쓰기를 거부했습니다'` — `path` · `reason` | `reason=mismatch` 가 이어지고 ops-api 의 커맨드가 401 로 거절된다(`sql ops "SELECT action, result, created_at FROM audit_logs ORDER BY created_at DESC LIMIT 20"` 에 `REJECTED` 가 줄지어 있다) → 서비스 사이의 토큰이 어긋났다: `.env` 의 `DAWNLINE_INTERNAL_TOKEN` 이 한쪽 기동 뒤에 바뀌었다 — 같은 `.env` 로 둘 다 다시 띄운다 · `reason=missing` 이고 감사 행이 없다 → ops-api 를 거치지 않는 누군가가 코어 포트를 부른다. RB-05 의 재큐 `curl` 을 토큰 없이 누른 사람일 수 있다 — 거부됐으므로 아무것도 적용되지 않았다. 누가 왜인지는 사람이 본다 | — |
| `DawnlineOpsCommandUnknown` | **SQL** `sql ops "SELECT id, action, target_id, actor, created_at FROM audit_logs WHERE result IN ('UNKNOWN', 'PENDING') AND action <> 'DLQ_REPLAY' ORDER BY created_at"` — 행의 `id` 가 코어 로그의 `auditId` 다 | `CLOSE_WAVE` · `REQUEUE_OUTBOX` → **다시 누르기가 먼저다** — 이미 적용됐으면 409 가 지금 위치를 말한다 · `RUN_PLAN` · `REASSIGN_STOP` · `CANCEL_ORDER` → 코어 로그에서 `auditId` 를 찾는다 — **성공의 줄이 있으면 적용됐고, 줄이 없다는 것은 아무것도 말하지 않는다**(코어는 성공만 로그하고 `RUN_PLAN` 은 그것도 없다) — 그때는 코어의 현재 상태 | RB-07 |
| `DawnlineRetentionStalled` | **로그** 알림의 `service` 에서 그 표의 정리 실패 — `logs <service> 2d \| grep '정리 실패'` 의 예외. 문자열은 표마다 다르다: 「보존 정리 실패」(fulfillment · dispatch) · 「tracking 보존 정리 실패」 · 「읽기 모델 보존 정리 실패」(ops-api) · 「processed_events 정리 실패」 · 「outbox 정리 실패」 · 「idempotency_keys 정리 실패」 | 알림은 `service` · `table` 을 함께 싣는다 — `outbox_events` · `processed_events` 는 서비스마다 다른 표다. `shipment_events` 는 파티션 회전이다(RB-06 §1). 정리는 예외를 삼킨다 — 원인을 고치면 **다음 실행이 밀린 것을 이어서 지운다**(배치 상한에 걸린 실행도 성공이다). 대부분 DB 다(RB-02). 급한 것은 아니다 — 용량 문제지 정확성 문제가 아니다(ADR-058) | — |

---

## 2. 알림 밖 절차

알림이 없는 데는 이유가 있다 — 셋 다 **0 이 정상이 아니거나**(2.1 의 간선, 2.2 의 진행 중) **셀 칸이 아직 없다**(2.3). 그래서 누가
먼저 알아채는지가 절차의 첫 칸이다.

### 2.1 트레이스가 끊겼다

**먼저 본다 — 메트릭** `sum by (client, server, connection_type) (traces_service_graph_request_total)` — 코어 넷 사이의 간선(`messaging_system`)이 있는가.

- **언제**: `make obs-check` 4 · 5 가 빨갛다, 또는 Tempo 에서 `{ span.dawnline.wave_id = "<waveId>" }` 의 결과가 서비스 하나를 빠뜨린다.
- **갈래** — 두 증거는 다른 것을 본다(§9.2): TraceQL 은 「컨텍스트가 건너갔는가」, 서비스 그래프는 「경계에 발행 · 소비 스팬의 쌍이 섰는가」.

| 간선 | TraceQL | 뜻 | 볼 곳 |
|---|---|---|---|
| 있다 | 서비스 하나가 빠진다 | 그 서비스가 받은 레코드에서 소비 스팬을 열지 않거나 스팬을 내보내지 않는다 | 그 서비스의 `spring.kafka.listener.observation-enabled`(꺼지면 컨텍스트가 거기서 끊긴다) · `management.opentelemetry.tracing.export.otlp.enabled`(꺼지면 전파는 되고 스팬만 없다 — lean 구성이 그렇다) |
| 없다 | 트레이스는 서비스 여럿을 지난다 | 트레이스는 이어지는데 **발행 스팬이 없다** — 템플릿 관측이 꺼졌다. 릴레이는 저장된 `traceparent` 를 그대로 보내므로 소비 스팬의 부모가 쓰기 스팬이 된다 | `spring.kafka.template.observation-enabled` · Tempo 에서 `{ kind = producer }` 가 0 개인지 |
| 없다 | 트레이스마다 서비스가 하나다 | 전파가 꺼졌다 — 서비스마다 제 트레이스만 갖는다 | `management.tracing.export.enabled=false` 는 **전파기까지 거둔다**(Boot 4.1, §9.2). 내보내기만 끄려면 `…export.otlp.enabled=false` 다 |
| 없다 | 결과가 없다 | 스팬이 Tempo 에 닿지 않는다 | OTel Collector 와 Tempo(`dc ps otel-collector tempo`) · 서비스의 `DAWNLINE_OTLP_TRACES_ENDPOINT` |

- **설정을 확인하는 법**: 키는 `libs/observability` 의 `observability-defaults.yml` 이 기본값을 주고(둘 다 `true`), compose 가 환경 변수로 덮을 수 있다 —
  `dc exec <service> env | grep -E 'TRACING|OBSERVATION|OTLP|SAMPLE'` — **비어 있으면 기본값이다**(로컬 `make up` 이 그렇다). lean 구성은
  `MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED=false` 하나가 보인다(`docker-compose.lean.yml`).
- **표본을 의심한다**: `DAWNLINE_TRACE_SAMPLE_RATE` 가 1 보다 작으면 트레이스 일부만 남는다 — 「이 웨이브의 트레이스에 tracking 이 없다」가
  결손이 아니라 표본일 수 있다. 피크 시나리오가 낮추는 값이다(§8.2).
- **간선이 서도 완전하다는 뜻은 아니다** — 소비자가 여럿인 토픽에서 발행 스팬 하나는 한 소비자와만 짝지어지는 것으로 보인다(근거: 추정, §9.2).
  그래프는 **존재 증명이지 완전성 증명이 아니다** — 간선 하나가 없다는 것만으로는 그 경계가 끊겼다고 말할 수 없고, 그때는 TraceQL 이 답이다.

### 2.2 `dawnline_routes{status="in_progress"}` 가 줄지 않는다

**먼저 본다 — SQL** 결과가 없는 주문 — 진행 중 라우트의 어느 주문이 끝나지 않았나:

```bash
sql ops "SELECT r.route_id, r.departed_at, o.order_id, o.order_status, o.planned_arrival
           FROM rm_routes r JOIN rm_orders o ON o.route_id = r.route_id
          WHERE r.status = 'DEPARTED' AND r.completed_at IS NULL AND r.live_count > 0
            AND o.delivery_outcome IS NULL AND o.order_status IS DISTINCT FROM 'CANCELLED'
          ORDER BY r.departed_at, o.planned_arrival LIMIT 50"
```

- **언제**: Delivery 대시보드의 라우트 진행에서 배송 창이 지났는데 `in_progress` 가 그대로다. 알림이 없는 이유 — 끝나지 않은 일에는 창이 없어서
  (ADR-061) 이 값은 **끝날 때까지 남는 것이 설계**다. 줄지 않는 것 자체가 신호이고, 얼마나 오래를 이상으로 볼지는 배송 창이 정한다.
- **갈래** — 그 주문의 사실이 어디까지 왔나. 위에서 나온 `order_id` 로 tracking 을 본다:

```bash
sql tracking "SELECT order_id, route_id, stop_seq, status, delivered_at FROM shipments WHERE order_id IN ('<id>', …)"
```

| tracking 의 `shipments.status` | 뜻 | 볼 곳 |
|---|---|---|
| `COMPLETED` · `FAILED` | 사실은 있는데 ops-api 에 닿지 않았다 | tracking 의 발행(`dawnline_outbox_lag_seconds{service="tracking-service"}` · 격리, RB-05 §1) → ops-api 의 소비(`dawnline.delivery.status.v1.dlq` 에서 원래 그룹이 `ops-api` 인 레코드, RB-05 §2) |
| `OUT_FOR_DELIVERY` · `ARRIVED` | 스캔이 오지 않았다 — 기사 단말이 결과를 찍지 않았다 | 추적 앱(로컬에서는 sim-runner 의 기사 시뮬레이터 — 그 라우트를 끝까지 돌았는가). `sql tracking "SELECT type, occurred_at FROM shipment_events WHERE order_id = '<id>' ORDER BY occurred_at"` 가 마지막으로 온 스캔을 말한다 |
| `SCHEDULED` | 출발 스캔조차 이 주문에 닿지 않았다 — 라우트는 출발했다 | 그 라우트가 개정됐는가(`route_revisions` · `dawnline_scan_after_relocate_total`). 재계획이 이 주문을 옮겼는데 ops-api 의 `route_id` 가 옛 라우트면 `route.assigned` 의 소비를 본다 |
| 행이 없다 | tracking 이 이 라우트를 모른다 | `route.assigned` 가 tracking 에 닿지 않았다 — `dawnline.route.assigned.v1.dlq`(원래 그룹 `tracking-service`, RB-05 §2) |

- **dispatch 도 같은 사실을 기다린다** — 끝나지 않은 stop 은 dispatch 에서 계획을 종결시키지 않고 30일 뒤 `dawnline_route_plans_stuck` 으로
  센다. 같은 `delivery.status` 의 DLQ 에서 원래 그룹이 `dispatch-service` 인 레코드가 그쪽이다.
- **읽기 모델을 SQL 로 고치지 않는다.** `rm_*` 은 프로젝션이다 — 고치면 다음 재집계가 덮고, 코어와 어긋난 채 남는다. 사실을 만드는 자리는 tracking 의
  스캔 하나이고, 거기서 흐르면 세 서비스가 함께 닫힌다.

### 2.3 조기 마감을 거듭한 날의 미제공

**먼저 본다 — SQL** 오늘 `MAX_PUSHES_EXCEEDED` 로 끝난 주문이 어느 캠프에, 언제 몰렸나:

```bash
sql fulfillment "SELECT c.code, date_trunc('hour', f.updated_at) AS hour, count(*)
                   FROM fulfillment_orders f JOIN camps c ON c.id = f.camp_id
                  WHERE f.status = 'UNSERVICEABLE' AND f.unserviceable_reason = 'MAX_PUSHES_EXCEEDED'
                    AND f.updated_at > now() - interval '1 day'
                  GROUP BY 1, 2 ORDER BY 2"
```

배차 불가 행은 웨이브에 들지 않았으므로 `cutoff_at` · `wave_id` 가 비어 있다 — 티어는 order-service 의 주문(`orders.service_tier`)이 안다.
로컬 실측(2026-09-25): 조기 마감이 넷 쌓인 `dawnline_*` 볼륨에서 `make smoke` 200건 중 `CAMP-SEO-N` 의 19건이 이 사유였다.

- **무슨 일인가**([ADR-063](../adr/ADR-063-no-open-wave-is-not-a-late-event.md)): 주문의 원래 컷오프 웨이브가 닫혀 있으면 다음 컷오프로
  밀고, `MAX_WAVE_PUSHES`(3)번 밀어도 열린 웨이브가 없으면 받을 곳이 없다. 스케줄러는 컷오프 전에 닫지 않으므로 이것은 **운영자가 같은
  캠프 · 티어의 웨이브 넷을 연달아 조기 마감한 결과**다(ADR-054). `STALE_PLACED`(이벤트가 24시간 넘게 늦게 왔다)와 원인이 반대편이다.
- **언제**: 알림이 없다 — 배차 불가에는 사유별 카운터가 없다(ADR-063 재검토 지점). 드러나는 자리는 셋이다: ops-web 캠프 대시보드의 배차
  불가 수 · 고객 문의(order-service `orders.failure_reason`) · fulfillment 의 경고 로그 「웨이브를 3번 밀어도 열린 웨이브를 찾지
  못했습니다」(`원래_웨이브_마감=MANUAL` 이 이 절차의 표지다).
- **닫힌 웨이브와 누가 닫았는지**:

```bash
sql fulfillment "SELECT w.id, w.cutoff_at, w.status, w.close_cause, w.closed_at
                   FROM waves w JOIN camps c ON c.id = w.camp_id
                  WHERE c.code = '<CAMP>' AND w.service_tier = '<TIER>' AND w.cutoff_at > now() - interval '1 day'
                  ORDER BY w.cutoff_at"
sql ops "SELECT target_id AS wave_id, actor, request->>'reason' AS reason, result, created_at
           FROM audit_logs WHERE action = 'CLOSE_WAVE' ORDER BY created_at DESC LIMIT 20"
```

- **대응**:
  1. **그 캠프 · 티어의 조기 마감을 멈춘다.** 닫힌 웨이브는 다시 열리지 않는다 — 웨이브에 되여는 전이가 없다(§5.2).
  2. **끝난 주문은 되살아나지 않는다.** `UNSERVICEABLE` 은 종결이고 order-service 는 `FAILED` 다. DLQ 에 없으므로 재처리 대상도 아니다 — 처리된
     이벤트다. 다시 받는 것은 고객에게 다시 접수받는 사람의 일이다(`STALE_PLACED` 와 같은 결론, RB-05 §2.2).
  3. **소진은 며칠 이어진다.** 컷오프가 C 인 주문은 C · C+1 · C+2 · C+3 일의 웨이브 중 첫 OPEN 에 들어간다. 닫힌 넷 뒤에 열린 웨이브가 있으면 그
     뒤 며칠의 주문은 실패가 아니라 **며칠 뒤로 밀린 약속**을 받는다 — `dawnline_promise_revised_total{camp, cause="manual"}` 이 그 수다.
     닫힌 웨이브의 컷오프가 하나씩 지나면서 밀림이 줄어든다.
- **예방** — 조기 마감을 누르기 전에 그 캠프 · 티어에 앞으로 열린 웨이브가 남는지 본다. 앞에 닫힌 웨이브가 `MAX_WAVE_PUSHES` 개면 이 마감이 다섯째
  칸을 닫는다(`tools/demo/phase2-demo.sh` 의 전제가 같은 질의다):

```bash
sql fulfillment "SELECT cutoff_at, status, close_cause FROM waves
                  WHERE camp_id = (SELECT id FROM camps WHERE code = '<CAMP>') AND service_tier = '<TIER>'
                    AND cutoff_at > now() ORDER BY cutoff_at LIMIT 6"
```

  **차례는 코드가 지킨다** — 더 이른 열린 웨이브가 있는데 뒤의 것을 누르면 409 `not-next-wave` 가 그 웨이브(`earlierWaveId`)를
  말하고 아무것도 닫지 않는다(ADR-054 후속). 코드가 막지 않는 것은 **거리**다 — 가장 이른 열린 웨이브가 며칠 뒤여도 닫힌다. 그래서 위의
  질의로 닫힌 웨이브가 몇 개 앞에 서 있는지를 누르기 전에 본다.

---

## 참조

- 개별 런북: [RB-01](RB-01-kafka-recovery.md) Kafka 복구 · [RB-02](RB-02-database-outage.md) DB 장애 · [RB-03](RB-03-redis-recovery.md) Redis 복구 ·
  [RB-04](RB-04-plan-stall-and-rerun.md) 계획 정체 · 재실행 · [RB-05](RB-05-dlq-and-outbox-quarantine.md) DLQ 재처리 · outbox 격리 재큐 ·
  [RB-06](RB-06-peak-readiness.md) 피크 대비 · [RB-07](RB-07-audit-unknown.md) 감사 `UNKNOWN` 해소
- `docs/DESIGN.md` §8.4 · §9.1 · §9.2 · §9.4 · §9.5
