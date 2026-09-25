# RB-06 — 피크 대비 체크리스트 · 파티션 여유

| 항목 | 내용 |
|---|---|
| 대상 | 피크(§8.2 — 평시의 5배, 컷오프 직전 1시간에 30%) 전에 확인할 것 · `shipment_events` 파티션 |
| 알림 | `DawnlinePartitionsAheadLow` ([README](README.md) 1) — 피크와 무관하게 울리지만 「날이 오기 전에 여유가 있는가」라는 같은 질문이라 여기 둔다 |
| 관련 설계 | §5.4(파티션) · §6.3 · §6.7 · §8.2 · §8.6 · 7-0 D1(콜드 스타트) |

**먼저 본다 — 메트릭** 여유 셋 — `prom 'min(dawnline_shipment_partitions_ahead)'`(파티션, 2 아래면 1) ·
`prom 'sum by (consumergroup, topic) (kafka_consumergroup_lag >= 0)'`(지금 밀려 있는 것 — 브로커 기준. 피크를 밀린 채 시작하지 않는다) ·
`prom 'histogram_quantile(0.95, sum by (le) (rate(dawnline_plan_duration_seconds_bucket[1d])))'`(평시 계획 p95 — 예산 30초에서 얼마나 남았나).

명령의 `dc` · `prom` · `logs` · `sql` 은 [README](README.md) 의 공통 준비다.

---

## 1. 파티션 여유 — `DawnlinePartitionsAheadLow`

`shipment_events` 는 일 파티션이고 DEFAULT 파티션이 없다(§5.4) — **그날의 파티션이 없으면 기사 스캔 INSERT 가 그 자리에서 실패한다.** 생성
스케줄러는 1시간마다 「어제부터 7일 앞」을 만들고, 게이지는 「앞으로 덮인 날 수」라 스케줄러가 멈추면 날마다 줄어든다. 알림은 2 에서 운다 —
여유는 이틀이다.

**원인**: `logs tracking-service 2h | grep 'shipment_events 파티션 관리 실패'` 의 예외. 대부분 DB 다(RB-02).

**원인을 고친 뒤 손으로 한 번 만든다** — 파티션 이름 · 경계는 마이그레이션의 함수가 정한다. 소유자 계정(`dawnline_tracking`)으로 부른다:

```bash
dc exec -T postgres psql -U dawnline_tracking -d dawnline_tracking \
  -c "SELECT tracking_ensure_event_partitions(current_date - 1, 9)" \
  -c "SELECT tracking_last_event_partition()"
```

같은 날짜를 다시 만들지 않으므로(있으면 건너뛴다) 여러 번 불러도 된다. 스케줄러가 돌아오면 게이지가 7 로 돌아간다. 지우기도 같은 실행이
한다 — 그쪽이 멈추면 `dawnline_retention_last_success_age_seconds{table="shipment_events"}` 가 말한다(§9.1 「여유는 양 끝에 있다」).

## 2. 피크 전날 — 체크리스트

| # | 확인 | 어떻게 | 기준 |
|---|---|---|---|
| 1 | 파티션이 피크일과 그다음 날을 덮는다 | `prom 'min(dawnline_shipment_partitions_ahead)'` | ≥ 피크까지 남은 날 + 2 |
| 2 | 밀린 것이 없다 | 소비 랙 · `dawnline_outbox_lag_seconds` · DLQ | 랙 < 1,000 · 지연 < 2초 · 미해결 DLQ 0 |
| 3 | 격리 · 걸린 행이 없다 | `dawnline_outbox_failed` · `dawnline_fulfillment_orders_stuck` · `dawnline_route_plans_stuck` | 0 (0 이 아니면 RB-05 · RB-04 §3) |
| 4 | 인스턴스 수와 파티션 | `dc ps` · 토픽 파티션 12 | 소비 인스턴스 × concurrency ≤ 12 — 넘는 소비자는 논다(§8.2) |
| 5 | DB 연결 총량 | 인스턴스당 풀 10 + 릴레이 세션 1 | 합이 PostgreSQL `max_connections` 안(`sql admin "SHOW max_connections"`) |
| 6 | 함대 | `sql dispatch "SELECT camp_id, count(*) FROM vehicles GROUP BY 1"` | 피크 물량을 실현 가능성 기준(80%)이 요구하는 대수(7-0 D2 — 7-4 의 peak-day 가 그 수를 낸다) |
| 7 | 룰 파라미터 | `GET /api/v1/rules`(dispatch, ops-api 경유) · 버전 | 피크용으로 바꿨다면 버전이 올랐고, 바꾼 사람과 이유가 이력에 있다 |
| 8 | 열화 설정 | `dawnline.dispatch.degrade.*`(랙 > 3 웨이브 → FAST, 직전 > 예산 × 0.8 → 개선 예산 × 0.5) | 바꾸지 않는다 — 피크에 쓰려고 있는 사다리다(ADR-034) |
| 9 | 표본 비율 | `DAWNLINE_TRACE_SAMPLE_RATE` | 피크에 낮춘다면 [README](README.md) 2.1 의 「표본을 의심한다」를 기억한다 |
| 10 | Redis | `dawnline_geo_index_loaded` 둘 다 1 · 폴백 카운터가 멈춰 있다 | RB-03 |

## 3. 예열 — 콜드 스타트는 배포 창의 문제다

**재현된 사실**(Phase 1, `docs/benchmarks/phase1-orders-k6.md` 6절): 기동 직후 약 80초 동안 `POST /orders` p99 가 **2~4초**이고 레이트 리밋이
fail-open 으로 우회된다. 원인 사슬은 하나다 — `SERVICE_CPU_LIMIT=0.75` 에서 JVM 이 SerialGC 를 고르고, 클래스 적재 중 full GC 가 몰려
요청이 커넥션을 쥔 채 멈추고, 풀이 포화한다. 웜 상태의 같은 구성은 p99 5~48 ms 다. **레디니스는 「뜰 준비」만 보고 「빠를 준비」는 보지 않는다**
(§8.6) — 그래서 피크 전에 사람이 한다:

1. **피크 전에 재기동하지 않는다.** 배포 · 재기동은 컷오프 창에서 멀리 둔다. 새 인스턴스가 트래픽을 받는 첫 80초가 그 창이다.
2. 재기동이 불가피하면 **트래픽 전에 데워진 것을 확인한다** — `make smoke` 한 번(주문 200건)이 적재 · 소비 · 계획 경로를 한 바퀴 돈다. 그
   뒤 `hikaricp_connections_pending` 이 0 이고 `http_server_requests_seconds` p99 가 웜 범위로 내려왔는지 본다.
3. CPU 한도가 2 아래면 GC 가 SerialGC 다 — `prom 'count by (service, gc) (jvm_gc_pause_seconds_count)'` 의 `gc` 가 `Copy` ·
   `MarkSweepCompact` 면 Serial, `G1 …` 이면 G1 이다. 바꾸는 것은 이 런북의 일이 아니다(후보 대응 — CPU 상향 · AppCDS · 워밍업 요청 · 레디니스에 워밍업 포함 — 은 7-0 A2 가 7-4 의 측정으로 판정한다).

첫 계획 · 첫 소비의 처리량이 정상 상태와 얼마나 다른지는 7-4 의 peak-day 가 콜드 스택에서 시작해 갈라 적는다(7-0 D1). **그 수치가 나오면
이 절의 80초를 그 값으로 바꾼다.**

## 참조

- `docs/DESIGN.md` §5.4 · §8.2 · §8.6 · §9.1
- `docs/benchmarks/phase1-orders-k6.md` 6절(콜드 스타트)
- [ADR-034](../adr/ADR-034-degrade-mode.md) — 열화 사다리
