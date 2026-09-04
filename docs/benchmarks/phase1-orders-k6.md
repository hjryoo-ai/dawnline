# Phase 1 — `POST /orders` 부하·레이트 리밋 측정

IMPLEMENTATION_PLAN Phase 1 DoD: *"k6 결과를 `docs/benchmarks/phase1-orders-k6.md` 에
기록(p50/p95/p99, 오류율). 목표 미달이면 원인 분석 포함."*

> **상태: 스크립트 완료 · 실측 미완료 (2026-09-05)**
> 스크립트(`tools/k6/orders.js`, `tools/k6/rate-limit.js`)와 `sim-runner` 는 `main` 에 있다.
> 아래 결과 표는 **비어 있고, 비어 있는 채로 커밋한다** — 채워지지 않은 칸이 남아 있어야
> Phase 1 이 아직 닫히지 않았다는 사실이 표에 보인다.

**미달 자체는 Phase 1 실패가 아니다. 원인을 모르는 것이 실패다.** 그래서 이 문서는 숫자를
적는 자리보다 [원인 판정표](#4-원인-판정표)를 먼저 갖췄다. 판정 기준을 <em>측정 전에</em>
적어 두는 이유는, 숫자를 본 뒤에 기준을 만들면 어떤 결과든 설명이 되기 때문이다.

---

## 1. 목표 (DESIGN.md §8.1, 데모 환경 기준)

| SLI | 목표 |
|---|---|
| `POST /orders` p99 지연 | ≤ 200 ms (500 rps 지속 시) |
| `POST /orders` 가용성 | ≥ 99.9% (5xx 비율 < 0.1%) |
| Outbox 지연 | p95 ≤ 2초 |

§8.1 이 "데모 환경 기준, 실측으로 갱신" 이라고 적은 그대로다. 단일 노드 Compose 에서
PostgreSQL·Kafka·Redis·관측성 스택과 서비스 5개가 같은 머신을 나눠 쓰므로, 이 숫자는
용량 계획이 아니라 **회귀 감시선**이다.

---

## 2. 측정 절차

```bash
# 1. 측정 대상 커밋을 명시한다. main 이어야 한다 — 머지되지 않은 코드의 수치는
#    다음에 누구도 재현할 수 없다.
git rev-parse --short HEAD

# 2. 스택 기동. 관측성 스택이 CPU 를 나눠 쓰는 것이 부담되면 up-lean 을 쓰되,
#    그 경우 HikariCP·outbox 지표는 /actuator/prometheus 를 직접 긁어 본다.
make up

# 3. 부하 — 워밍업 50 rps × 20초 뒤 500 rps × 60초
make k6-orders                 # 또는 k6 run tools/k6/orders.js
#    → summary.md / summary.json 이 생긴다. summary.md 를 3절 표에 그대로 붙인다.

# 4. 부하 직후(스택을 내리기 전에) 아래를 긁어 5절에 적는다
curl -s localhost:8081/actuator/prometheus | grep -E \
  'hikaricp_connections_pending|hikaricp_connections_active|dawnline_outbox_lag_seconds|dawnline_rate_limit_decisions_total|jvm_gc_pause_seconds_sum'

# 5. 레이트 리밋 계약 검증 (부하와 섞이지 않게 따로 돌린다)
make k6-rate-limit

# 6. sim-runner smoke 200건
make smoke
```

**측정에서 뺀 것**: 워밍업 20초. JIT·커넥션 풀·Hibernate 프록시가 데워지기 전의 숫자는
서비스의 지연이 아니다. `orders.js` 가 `phase:warmup` 태그로 분리하고, 임계값은
`http_req_duration{phase:measure}` 에만 건다.

**측정에 섞인 것**: k6 를 order-service 와 같은 호스트에서 돌리면 CPU 를 나눠 쓴다.
`dropped_iterations` 가 0 이 아니면 그 영향이 실제로 나타난 것이다(4절 참고).

---

## 3. 부하 결과 — `orders.js`

측정 커밋: `—(미측정)` · 측정 일시: `—(미측정)` · 호스트: `—(미측정)`

<!-- k6 가 만든 summary.md 를 여기에 그대로 붙인다. 손으로 옮겨 적지 않는다 —
     옮기는 사람이 반올림하고, 반올림한 숫자는 다음 측정과 비교할 수 없다. -->

| 항목 | 값 |
|---|---|
| 목표 부하 | 500 rps × 60s (워밍업 50 rps × 20s 제외) |
| 고객 수 | 10,000 |
| 총 요청 | —(미측정) |
| 버려진 반복 (`dropped_iterations`) | —(미측정) |
| p50 | —(미측정) |
| p95 | —(미측정) |
| p99 | —(미측정) |
| max | —(미측정) |
| 201 접수 | —(미측정) |
| 200 멱등 재생 | —(미측정) |
| 4xx (429 제외) | —(미측정) |
| 429 | —(미측정) |
| 5xx | —(미측정) |
| — `validation-failed` | —(미측정) |
| — `tier-not-serviceable` | —(미측정) |
| — `idempotent-request-in-flight` | —(미측정) |
| — 그 밖의 code | —(미측정) |

**SLO 대비 판정**

| SLI | 목표 | 실측 | 판정 |
|---|---|---|---|
| p99 | ≤ 200 ms | —(미측정) | — |
| 5xx 비율 | < 0.1% | —(미측정) | — |
| 부하가 실제로 걸렸는가 | `dropped_iterations` == 0 | —(미측정) | — |

> 마지막 줄이 먼저다. `dropped_iterations` 가 0 이 아니면 실제 부하가 500 rps 에 못 미쳤고,
> 그때의 p99 는 "500 rps 에서의 p99" 가 아니다. **달성도 미달도 말할 수 없다.**

---

## 4. 원인 판정표

측정 전에 적는다. 어떤 값이 어떤 원인을 가리키는지를 미리 정해 두면, 숫자를 본 뒤에
그럴듯한 이야기를 만들 여지가 없다.

| 관측 | 원인 | 확인할 것 | 다음 행동 |
|---|---|---|---|
| `dropped_iterations` > 0 | 부하 생성 실패 — VU 가 모자랐거나 k6 호스트가 포화 | k6 요약의 `vus_max`, 호스트 CPU | `maxVUs` 를 올리거나 k6 를 다른 머신에서 돌린 뒤 **재측정**. 이 회차 p99 는 버린다 |
| `problem_validation_failed` > 0 | **스크립트 문제.** `lib/orders.js` 의 우편번호 표가 `PostalPrefixGeocoder.ANCHORS` 와 어긋났다 | 두 표를 비교 | 표를 맞추고 재측정. 서비스는 잘못이 없다 |
| `429` > 0 | 고객 분산이 깨졌다 | `-e CUSTOMERS` 값, `order_rate_limited` | 1만 명 분산을 확인하고 재측정 |
| `200 멱등 재생` > 0 | 멱등 키가 겹쳤다 — 앞선 실행과 같은 `RUN_ID` | `RUN_ID` 는 실행 시각 기반이라 정상적으로는 겹치지 않는다 | 재측정. 재생 응답은 DB 를 건드리지 않아 지연을 **낮춘다** |
| p99 미달 + `hikaricp_connections_pending` > 0 | **DB 커넥션 풀 포화**(인스턴스당 10, §8.2) | 대기 시간, 접수 트랜잭션의 쿼리 수 | §8.3 전역 Bulkhead 를 **Phase 1 으로 당긴다**(6절) |
| p99 미달 + `pending` == 0 + `rate_limit_decisions{outcome="bypassed"}` 증가 | **Redis 지연**. 멱등 캐시·레이트 리밋이 둘 다 핫패스에 있다 | `dawnline.order.redis.command-timeout-ms`(50ms)와 차단기(10초) | 타임아웃·차단기가 의도대로 작동했는지 먼저 본다. 작동했다면 이 지연은 상한이 걸린 것이다 |
| p99 미달 + `jvm_gc_pause_seconds_sum` 급증 | GC | 힙 크기, 할당률 | 힙을 올려 재측정. 원인이 GC 라는 것을 기록에 남긴다 |
| p99 미달 + `dawnline_outbox_lag_seconds` 상승 | 릴레이의 쓰기가 접수 트랜잭션과 같은 DB 를 두고 경합 | 릴레이 폴링 주기(100ms)·배치 크기 | Phase 7 신뢰성 항목으로 넘길지 판단하고 근거를 적는다 |
| 5xx > 0 | 서비스 오류 | 구조화 로그의 `code`·스택 | 원인별로 나눠 적는다. 0.1% 미만이어도 **무엇이었는지는 적는다** |
| p50 은 낮은데 max 만 큼 | 단발 지연 — 워밍업 잔여·GC·컨테이너 스케줄링 | `phase` 태그가 실제로 갈라졌는지 | p99 가 목표 안이면 max 는 기록만 한다 |

어느 줄에도 해당하지 않으면 **그것 자체가 결과다**. "원인 미상" 이라고 적고 다음에 볼 것을
적어 둔다 — 빈칸으로 두지 않는다.

---

## 5. 함께 기록하는 값

부하 직후, 스택을 내리기 전에 긁는다.

| 지표 | 값 | 왜 보는가 |
|---|---|---|
| `hikaricp_connections_pending` (최대) | —(미측정) | §8.3 Bulkhead 를 Phase 1 으로 당길지 판정 (6절) |
| `hikaricp_connections_active` (최대) | —(미측정) | 풀 10 중 몇 개를 실제로 썼는가 |
| `dawnline_outbox_lag_seconds` (p95) | —(미측정) | §8.1 목표 2초 |
| `dawnline_outbox_unpublished` (최대) | —(미측정) | 릴레이가 유입을 따라갔는가 |
| `dawnline_rate_limit_decisions_total{outcome="bypassed"}` | —(미측정) | 0 이 아니면 Redis 가 죽어 fail-open 중이었다는 뜻이고, 그러면 이 회차는 **레이트 리밋 없이** 잰 것이다 |
| `dawnline_idempotent_replays_total` | —(미측정) | 0 이어야 한다. 아니면 멱등 키가 겹쳤다 |
| `jvm_gc_pause_seconds_sum` (증분) | —(미측정) | 지연 꼬리의 원인 후보 |

---

## 6. §8.3 전역 Bulkhead — Phase 1 으로 당길지 판정

IMPLEMENTATION_PLAN 「Phase 7 로 이월 (조건부)」의 판정을 여기서 한다.

> **조건**: 9단계 k6 에서 HikariCP 풀(인스턴스당 10, §8.2) 포화가 관측되면 Phase 1 안으로 당긴다.

| 항목 | 값 |
|---|---|
| `hikaricp_connections_pending` 최대 | —(미측정) |
| 판정 | —(미측정) |

- `pending` 이 계속 0 이었다 → 풀이 포화되지 않았다. **Phase 7 로 그대로 둔다.**
- `pending` 이 0 을 넘어 유지되었다 → 요청이 커넥션을 기다렸다는 뜻이고, 그 상태에서는
  요청을 더 받아 봐야 지연만 늘어난다. **Bulkhead 를 Phase 1 으로 당긴다.**

---

## 7. 레이트 리밋 동작 검증 — `rate-limit.js`

**이 절은 부하 리포트가 아니다.** 통합 테스트의 연장이고 결과는 수치가 아니라 통과/실패다
(IMPLEMENTATION_PLAN Phase 1 DoD 의 마지막 항목). 같은 문서의 별도 절에 두는 이유가 그것이다.

검증하는 계약 (§7.2):

1. 한 고객이 계속 쏘면 429 가 나온다.
2. 429 에는 `Retry-After` 가 있고 201 에는 없다.
3. 429 본문은 Problem Details 이고 `code` 는 `rate-limited`, `type` 이 채워져 있다.
4. 버킷은 용량 60 · 초당 1 리필이다 — 40초 × 5 rps(200건) 중 통과는 100건 언저리다.
5. **멱등 재요청도 한도를 소모한다.** 이미 접수된 키를 다시 보내도 버킷이 비어 있으면 429 다.
   리필 뒤에는 같은 키가 200 재생으로 돌아온다 — 리밋은 거절이 아니라 지연이다.

| 항목 | 기대 | 실측 | 판정 |
|---|---|---|---|
| 보낸 요청 | 200 | —(미측정) | — |
| 통과(201) | 60 ~ 105 | —(미측정) | — |
| 차단(429) | 75 이상 | —(미측정) | — |
| 예상 밖 status | 0 | —(미측정) | — |
| 체크 통과율 | 100% | —(미측정) | — |

> 5번 조항이 없으면 한도를 우회하는 방법이 생긴다 — 키 하나를 계속 재전송하면 되기 때문이다.
> 무인증 API(§10)에서 레이트 리밋은 유일한 남용 방지 수단이므로, 이 조항은 성능이 아니라
> **보상 통제**의 문제다.

---

## 8. sim-runner smoke

| 항목 | 기대 | 실측 |
|---|---|---|
| 보낸 주문 | 200 | —(미측정) |
| 접수(201) | 200 | —(미측정) |
| 종료 코드 | 0 | —(미측정) |

`make smoke` 는 하나라도 접수되지 않으면 0 이 아닌 코드로 끝난다. 부하가 아니라 **흐름**을
만드는 것이 목적이므로 여기의 p50/p95/p99 는 참고값이고 SLO 판정에 쓰지 않는다.

---

## 9. 결론

`—(미측정)`

<!-- 채울 때: (1) SLO 대비 달성/미달을 항목별로, (2) 미달이면 4절 판정표의 어느 줄이었는지와
     그 근거 수치, (3) 다음 행동(재측정·튜닝·다음 Phase 이월)을 적는다. -->
