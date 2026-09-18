# Dawnline

**당일·새벽 배송 오더 오케스트레이션 & 디스패치 플랫폼** — 주문 접수부터 기사 경로 배정까지를
룰 엔진과 비용 기반 경로 최적화로 푸는, 이벤트 드리븐 MSA 포트폴리오 프로젝트.

> ### 현재 상태: **Phase 0 (스캐폴딩) 진행 중**
>
> 이 README는 **골격**이다. 아직 구현되지 않은 것을 구현된 것처럼 쓰지 않는다.
> Phase 0의 범위는 **빌드**와 **로컬 인프라 스택 기동**, 그리고 헬스 체크만 노출하는 **빈 서비스 5개**까지다.
> 비즈니스 로직은 Phase 1부터 들어간다.
> 벤치마크 수치·정시율·데모 화면은 측정하거나 만든 뒤에 채운다. 자리표시자에는 **어느 Phase에서 채우는지**를 적었다.
> 진행 상황은 [현재 구현 현황](#현재-구현-현황)을 참고.

---

## 무엇을 푸는가

새벽·당일 배송은 "주문을 받는 일"이 아니라 **마감 시각까지 물량을 경로로 바꾸는 일**이다.
자정 컷오프에 캠프 하나로 수천 건이 몰리고, 그 전부를 수십 대의 차량에 나눠 담아 순서를 정해야 한다.
그것도 냉장 물량은 냉장 차량에만, 기사 근무시간 안에, 약속한 배송창을 지키면서, 가능한 한 싸게.

이 프로젝트가 증명하려는 것은 두 가지다.

1. **룰 기반 최소 비용 배송 알고리즘** — 하드/소프트 룰 엔진 + 비용 모델 + 휴리스틱 파이프라인.
   그리고 "왜 이 주문이 이 차량에 갔는가 / 왜 미배정인가"를 운영자가 조회할 수 있는 **설명 가능성**.
2. **성수기에도 무너지지 않는 이벤트 드리븐 MSA** — Outbox와 멱등 소비자로 유실·중복을 구조적으로 막고,
   계획이 느려지면 품질을 낮춰서라도 마감을 지키는 **명시적 열화(degrade) 경로**.

### 도메인 요약

| 용어 | 정의 |
|---|---|
| **FC** | 재고를 보관·피킹하는 물류센터. 여러 캠프에 물량을 공급 |
| **Camp** | 라스트마일 출발 거점. 차량·기사가 소속됨 |
| **Zone** | 캠프 하위 배송 구역 (geohash 5자리 prefix 집합) |
| **Wave** | (캠프, 티어, 컷오프) 단위로 묶인 주문 집합. **계획의 단위** |
| **Route** | 차량·기사 1회 출발의 배송 계획. 순서 있는 Stop 목록 |
| **Stop** | 하나의 배송지 방문. 같은 주소의 여러 주문은 하나의 Stop으로 통합 |
| **Plan** | 웨이브 하나에 대한 최적화 실행 1회와 그 결과(라우트·미배정·비용·설명) |

서비스 티어는 `DAWN`(새벽) / `SAME_DAY`(당일) / `NEXT_DAY`(익일)이며 티어마다 컷오프와 약속 배송창이 다르다.
자세한 정의는 [DESIGN.md §2](docs/DESIGN.md)를 참고.

---

## 아키텍처 개요

### 엔드투엔드 흐름

```
고객/시뮬레이터
   │ POST /orders (Idempotency-Key)
   ▼
[order-service] ──order.placed──▶ [fulfillment-service]
                                    │ FC 선택 · 캠프/권역 결정 · 웨이브 편입
                                    ├──fulfillment.planned──▶ [dispatch-service] (후보 적재)
                                    │ (컷오프 스케줄러, 분산 락)
                                    └──wave.closed──────────▶ [dispatch-service]
                                                                │ 룰 엔진 → 최적화 → 라우트
                                                                ├──route.assigned──▶ [tracking-service]
                                                                └──order.dispatched─▶ [order-service] (상태 갱신)
시뮬레이터 ──기사 스캔(arrived/completed/failed)──▶ [tracking-service]
                                                      ├──delivery.status──▶ [order-service], [ops-api]
                                                      └──delivery.at-risk─▶ [dispatch-service] (부분 재계획)
모든 이벤트 ──▶ [ops-api] 읽기 모델(프로젝션) ──▶ [ops-web] 운영 콘솔
```

### 설계의 뼈대

- **서비스 간 쓰기 경로는 이벤트만.** 코어 서비스끼리 동기 REST 호출을 하지 않는다.
  필요한 데이터는 이벤트 페이로드 스냅샷이거나 자기 DB 프로젝션이다. 동기 조회는 `ops-api` → 코어 방향만 허용한다.
- **DB-per-service.** 서비스는 자기 DB만 본다. 크로스 서비스 JOIN·FK는 금지다.
- **Transactional Outbox.** 도메인 상태 변경과 이벤트 발행이 같은 DB 트랜잭션에 들어간다.
  Kafka는 이 트랜잭션에 참여할 수 없으므로(→ [ADR-006](docs/adr/ADR-006-at-least-once-idempotent-consumer.md)),
  원자성을 PostgreSQL 하나로 환원한다.
- **at-least-once + 멱등 소비자.** 중복은 제거 대상이 아니라 흡수 대상이다.
  모든 리스너가 `processed_events(event_id, consumer)` 를 비즈니스 트랜잭션과 같은 트랜잭션에서 기록한다.
- **헥사고날 + ArchUnit.** 경계는 문서가 아니라 테스트로 강제한다. 특히 `dispatch-service` 의 `domain.optimizer` 는
  Spring에 의존하지 않는 순수 Java여서, 벤치마크 도구가 서비스와 **똑같은 코드**를 실행한다.

각 결정의 근거와 기각한 대안은 [docs/adr/](docs/adr/README.md)에 있다.

---

## 핵심: 디스패치 최적화 엔진

웨이브 하나에 대해, 캠프의 차량 집합과 후보 주문 집합이 주어졌을 때 **총비용을 최소화하는 라우트 집합**을 찾는다.

```
cost(R) = Σ_r [ fixed(v) + dist_km·perKm(v) + dur_min·perMin(v) + Σ_stop late_min·penaltyPerMin ]
        + Σ_{미배정} unassignedPenalty(o)
        + Σ 소프트 룰 페널티
```

시간창이 있는 용량 제약 차량 경로 문제(CVRPTW)의 변형이며 NP-hard다. 따라서 "최적"을
**주어진 시간 예산 안에서 베이스라인 대비 검증된 개선**으로 정의하고, 벤치마크로 증명한다.

파이프라인: `Stop 통합 → Sweep 클러스터링 → Greedy 차량 할당 → Nearest-Neighbor 시퀀싱 → Local Search 개선 → 하드 룰 재검증`.
각 단계는 교체 가능한 클래스이고, 전략(`baseline-nn`, `sweep-greedy-nn`, `sweep-greedy-nn+ls`, `savings-cw+ls`)은
플러그인으로 등록된다. 룰은 코드가 아니라 **DB의 데이터**이며, 하드 룰 위반과 소프트 룰 페널티는 모두
`Explanation` 으로 남아 운영자가 조회할 수 있다.

**벤치마크** ([`docs/benchmarks/phase4-strategies.md`](docs/benchmarks/phase4-strategies.md) — Phase 4 마감 리포트, seed 고정, 커밋 단위 측정):

| 데이터셋 | `baseline-nn` | `sweep-greedy-nn` | `sweep-greedy-nn+ls` (기본) | `savings-cw+ls` | 계획 p95 (기본 / savings) |
|---|---:|---:|---:|---:|---:|
| small (500주문 / 5대) | 1,517,523 | 1,277,725 (−15.80%) | **1,113,911 (−26.60%)** | 1,262,833 (−16.78%) | 232 / 342 ms |
| medium (2,000 / 20) | 4,588,065 | 4,285,740 (−6.59%) | 3,791,148 (−17.37%) | **3,656,891 (−20.29%)** | 1,289 / 761 ms |
| large (5,000 / 40) | 9,577,578 | 9,212,551 (−3.81%) | 8,147,294 (−14.93%) | **7,834,800 (−18.20%)** | 3,362 / 1,901 ms |
| peak (15,000 / 88) | 28,369,930 | 26,072,660 (−8.10%) | 21,509,847 (−24.18%) | **20,737,822 (−26.90%)** | 18,798 / 13,164 ms |

> 위 표는 **실현 가능한 네 데이터셋**이다(다섯 전부 「수렴 종료」 — 어느 회차도 계획 마감에 잘리지
> 않았다). 리포트에는 과부하 `overload`(수요가 슬롯의 **146%** — 재는 것이 라우팅 품질이 아니라
> **미배정 정책·계획 시간의 상한·열화**다)가 **별도 절**로 있고, 여기에 **고정비 하한**(불가능의
> 경계), **열화 사다리 두 단의 전략별 대가**, **재기준 여섯 번의 이력**, **그림자 계측 원장 여덟
> 줄**이 함께 있다.

> **§6.7 의 「≥ 15% 절감」은 `large` 에서 −18.20% 로 넘었다 — 다만 그것을 낸 전략은 기본 전략이
> 아니다** ([ADR-042](docs/adr/ADR-042-savings-merges-are-class-aware.md),
> [ADR-043](docs/adr/ADR-043-default-strategy-stays-until-peak-converges.md),
> [ADR-044](docs/adr/ADR-044-endpoints-are-few-enough-to-see-all.md)). Clarke-Wright
> savings 는 「자르고 붙인다」 대신 「이어 붙이며 키운다」로 라우트를 만들고, 그 한 가지 차이가
> `large` 의 주행거리를 **117 km** 줄이고 차량을 한 대 덜 쓴다. 병합 판정이 **제약 조합을 아는**
> 것이 이 구현의 요점이다 — CW 는 stop 을 이어 붙이며 라우트의 조합을 키우므로(냉장 stop 하나가
> 이웃 100개를 냉장차에 묶는다), [ADR-039](docs/adr/ADR-039-reserve-seats-by-constraint-class.md)
> 의 집계 좌석 불변식을 **배정이 아니라 구성 단계**에 걸었다. 붙이는 시점의 예약은 이미 만들어진
> 라우트를 고칠 수 없기 때문이다.
>
> **기본 전략이 되지 못한 이유는 두 번 바뀌었고, 둘 다 수치로 적혀 있다.**
>
> **처음은 재현성이었다.** `peak` 에서 30초 예산에 잘렸고 잘린 값이 실행마다 달랐다 —
> 20,940,782 / 21,173,960 / 20,940,782 / **23,277,318**. 마지막은 마감이 개선이 아니라 **배정을
> 끊어** 96 stop 이 `plan-deadline` 로 남은 실행이다. **마감이 무엇을 끊는지가 총비용이 얼마나
> 나쁜지를 정한다** — 개선을 끊으면 계획은 덜 좋을 뿐이지만 배정을 끊으면 계획에 구멍이 남는다.
> 그래서 바꾸는 조건을 **벽시계가 아니라 구조로** 먼저 적었다: 「구성 라우트 수 ≤ 차량 수 × 1.2」
> (`peak` 에서 106, 그때 216).
>
> **그 조건은 충족됐다** ([ADR-044](docs/adr/ADR-044-endpoints-are-few-enough-to-see-all.md), 4-19).
> K-최근접은 stop 이 8,411개일 때 필요한 근사인데, 1단계가 끝나면 라우트는 216개뿐이고 이을
> 자리는 끝점 46,090 쌍뿐이다 — 그 크기에서는 근사할 이유가 없다. 끝점을 전부 보니 라우트가
> **216 → 90**, `peak` 이 **13,164 ms 에 수렴**하고 기본 전략보다 **772,025원 싸다**. 넓힌 이웃
> 표(K=100)보다 **더 싸기까지 하다**(38 ms 대 206 ms — 표를 만드는 것이 `O(n²)` 다).
>
> **그런데 같은 변경이 `small` 을 깼다** — 1,073,363 → **1,262,833**(기본보다 +13.4%, 미배정
> 0 → 3). 구성이 라우트를 17 → 8 로 줄이면 부착이 5개를 받고 3개가 통째로 재삽입으로 내려가는데,
> **작은 데이터셋에서는 계획을 정하는 몫이 재삽입에서 구성으로 옮겨간 것 자체가 손해다.**
> 그래서 기본 전략은 이번에도 그대로이고, 남은 조건은 「`small` 에서 savings ≤ 기본」이다.
> **상한이 조건을 넘긴 것과 총비용이 좋아지는 것은 다른 명제**라는 것이 이 항목의 기록이다.

> **이 표는 2026-09-12 에 두 번 재기준됐다.** 하나는 자
> ([ADR-040](docs/adr/ADR-040-priority-boost-decays-in-time.md)), 하나는 클러스터의 축
> ([ADR-041](docs/adr/ADR-041-cluster-target-counts-stop-slots.md) — 「차 한 대 몫」에 stop
> 슬롯이 들어간다. 그동안 `large` 의 클러스터는 상한 120 에 244~341 stop 이었다). 자부터 고친
> 것은 순서가 결과를 만들지 않게 하기 위해서다 — 판정을 자보다 먼저 냈다면 「자를 바꿔서 이긴
> 것」이 됐을 것이다. 자 쪽은 이렇다:
> `priority-boost` 의 보너스가 **순번이 아니라 계획 도착 시각**으로 감쇠한다. 소프트 룰이 바뀌면
> 목적함수가 바뀌므로 **동결 `baseline-nn` 의 절대값도 함께 다시 냈다**(§6.9 — 동결이 지키는 것은
> 비교의 공정성이지 절대 수치가 아니다). `baseline-nn` 의 계획은 **한 stop 도 바뀌지 않았고**
> (거리·미배정·지각 동일) 차이가 전부 소프트 항이라는 것이 그 표에 있다.

> 네 데이터셋의 **미배정이 9·0·1·10 에서 0·0·0·1 이 됐다**(`peak` 포함). 그 전까지 남던 것은
> 거의 전부 **냉장 ∧ 위험물**이었다 — 바로 아래 문단이 그 이야기다.

> **그리고 최적해와는 얼마나 먼가 — 고정비 하한은 총비용의 하한이 아니다**
> ([ADR-038](docs/adr/ADR-038-fixed-cost-floor-is-not-a-total-cost-floor.md),
> [측정](docs/benchmarks/phase4-cluster-axis.md)). 동결 베이스라인은 «다른 휴리스틱» 이지
> «경계» 가 아니라서, 리포트에 **불가능의 경계**를 상시 열로 뒀다 — 기하·시간·순서를 전부 버리고
> 총량만 덮는 가장 싼 함대의 고정비다(제약 조합 × stop·중량·부피 축마다 분수 허용으로 덮어
> 최댓값). `large` 는 현재 2,500,000 대 하한 **1,738,000**(1.44배), `overload` 는 **실현 불가**로
> 나온다. **정수로 올리지 않는다** — 느슨해도 참인 쪽을 고른다.
>
> 그 열은 **자기 한계를 함께 말한다.** 「여유가 762,000원이니 거기서 가져오면 된다」가 4-8 을 연
> 판단이었는데, **잰 항이 틀렸다**: 고정비를 하한 근처까지 민 변형은 미배정 페널티가
> 30,000 → 1,660,000 이 됐고 늘어난 사유는 **전부 위험물**이었다 — 아낀 1원마다 2.9~4.0원을 문다.
> **빈 좌석은 낭비가 아니라 희소 능력 수요가 나중에 앉을 자리였다.** 그래서 이 열은 「얼마나 멀리
> 있는가」를 말하지 「가야 한다」를 말하지 않는다.
>
> **그 문장의 처방이 좌석 예약이다**
> ([ADR-039](docs/adr/ADR-039-reserve-seats-by-constraint-class.md)). 희소한 것은 「위험물」도
> 「냉장」도 아니라 **둘을 동시에 요구하는 자리**였다 — 능력별로 재면 `peak` 에서 위험물 차량의
> 여유 슬롯이 287개로 보이는데, 그 조합을 싣는 9대의 여유는 **5개**다. 그래서 예약의 단위를
> **제약 조합**으로 두고, 배정 단계 동안 그 자리를 닫는다. **상한도 이번에는 틀렸는데 방향이
> 반대였다** — 실제 회수가 `small` 354,487(상한 350,000) · `peak` 566,528(상한 290,000)으로
> 상한을 넘었다. 좌석 예약은 미배정만 걷지 않고 **배정 자체를 바꾸기** 때문이다.
> **상한은 「그 항」의 상한이지 「그 변경」의 상한이 아니다.**

> **`small` 레짐 격차 — 진단이 두 번 바뀌었다.** 처음엔 「자유도가 없다」로 읽었다: 클러스터링
> 단독(`sweep-greedy-nn`)이 `small` 에서 베이스라인에 **+6.1% 로 졌고**, 차량 4~5대면 다섯째 차의
> 고정비 45,000원이 순수한 손실로 보였다. 개선 단계가 그것을 −2.68% 로 뒤집었으므로 「격차는
> 닫혔고 여유는 얇다」가 오랫동안의 결론이었다.
> **그런데 지고 있던 이유는 기하가 아니라 좌석이었다.** `small` 의 총비용 1,490,513원 중
> **350,000원이 미배정 페널티**였고 그 9건이 전부 위험물 — 그 조합을 실을 수 있는 단 한 대의
> 120 자리가 **전부 일반 수요**로 차 있었기 때문이다. 좌석을 조합 단위로 예약하자
> (ADR-039) 클러스터링 단독이 **−15.9%** 로 뒤집히고 개선 단계까지 붙이면 **−25.82%**,
> 미배정은 **0** 이다(자와 축을 바꾼 뒤로는 −15.8% · **−26.60%** — ADR-040·041). 다섯째 차는
> 여전히 있다 — 그 예측만은 처음부터 맞았다.
> CI 회귀 게이트는 자유도가 처음 생기는 `medium` 에서 돈다(§6.9, 결과가 아니라 메커니즘이 기준이다).
>
> **그리고 레짐은 이제 둘이다 — 둘째는 열려 있다.** `savings-cw+ls` 가 네 데이터셋에서 이기고
> `small` 한 자리에서 기본 전략에 **13.4% 진다**(1,262,833 대 1,113,911, 미배정 0 → **3**).
> 이유는 안다: [ADR-044](docs/adr/ADR-044-endpoints-are-few-enough-to-see-all.md) 의 2단계가 구성
> 라우트를 17 → 8 로 줄이면 차 다섯에 다섯이 붙고 **셋이 통째로 재삽입으로 내려가는데**, 재삽입은
> stop 을 가장 싼 자리에 넣지만 **구성은 거리만 보고 순서를 정한다** — 작은 데이터셋에서는
> **계획을 정하는 몫이 재삽입에서 구성으로 옮겨간 것 자체가 손해다.** 두 레짐의 뿌리가 같다:
> `small` 에서는 구성이 정한 것을 뒤 단계가 되돌릴 여지가 적다. 후속은 **4-20**(Phase 4 백로그,
> Phase 7 의 peak-day 시뮬레이션이 다시 연다).

> **`large` 의 「≥ 15% 절감」 목표는 기본 전략에서는 −14.93% 로 6,353원이 모자란다 — 그리고 그것을 넘긴 것은 여섯 번째 열이었다.** 이 숫자는 다섯 번 재기준된 것이다. 처음 측정은 −11.84% 였는데, 총비용의 **34%가 누구도 실을 수 없는 미배정 페널티**였다(냉장 ∧ 위험물 차량이 데이터셋 크기와 무관하게 1대). 도구를 고치자
> −43.98% 가 나왔지만 그것은 자가 반대로 휜 것이었고(`baseline-nn` 이 늘어난 차량을 쓰지 못해 더 나빠졌다), §6.5 1단계의 통합 키에서 **제약 전파**를 없애자 베이스라인이 크게 좋아지며 −14.53% 가 남았다. 세 열의 기록은
> [`phase4-constraint-classes.md`](docs/benchmarks/phase4-constraint-classes.md) 에 있다 — «데이터로 통과시키지 않았다» 는 것을 그 표가 증명한다.
> 세 번째는 좌석 예약(ADR-039)이고, `large` 만 **−14.52% → −14.47% 로 졌다**(+5,516원). 진 자리는
> 소프트 룰 +46,536원이고 그중 **+31,336원이 `priority-boost`** — `bonusKrw × priority ÷ position`
> 은 라우트가 길어질수록 보너스가 줄어 **차를 더 쓰는 계획에 유리한 자**다. 4-8 의 축 정정이
> `large` 에서 졌던 것도 같은 항이었다(+83,923원). **같은 자가 두 항목을 연속으로 이겼다면 다음에
> 볼 것은 알고리즘이 아니라 자다.**
>
> 네 번째가 그 검토다([ADR-040](docs/adr/ADR-040-priority-boost-decays-in-time.md), 4-18). 네 정의를
> 그림자로 재 보니 순번 자가 **재려는 차이보다 크게 흔들렸다** — 거리·시간·지각이 전부 좋아진
> 계획(총비용 차이 −58,084원)을 그 항 하나가 **+64,018원**으로 벌했다. 자를 계획 도착 시각으로
> 바꾸자 `large` 의 기본 전략 주행거리가 **58 km 줄었다**. 그래도 −14.56% 였다 —
> **판단 기준에서 「어느 정의가 15%를 넘기는가」는 뺐고**, 그 문장을 ADR 본문에 박아 두었다.
>
> 다섯 번째가 클러스터의 축이다([ADR-041](docs/adr/ADR-041-cluster-target-counts-stop-slots.md)).
> 「차 한 대 몫」이 중량과 부피만 보고 있어서 `large` 의 클러스터가 **상한 120 에 244~341 stop**
> 이었다 — 차 두세 대 몫이다. stop 축을 더하니 주행거리가 다시 **82 km** 줄어 **−14.93%**,
> **DoD 까지 6,353원**이다. 대가도 적는다: 개선 단계가 없는 열화 모드에서는 `peak` 이
> +1,311,428원으로 나빠진다(§6.7 사다리는 4-5 에서 다시 낸다).
> `p95 ≤ 30초` 는 **3,303 ms 로 통과**한다.
>
> **여섯 번째는 6,353원을 쫓지 않기로 하고 다음 항목으로 간 결과다**
> ([ADR-042](docs/adr/ADR-042-savings-merges-are-class-aware.md), 4-2). §6.6 표에 처음부터 있던
> 비교 전략 `savings-cw+ls` 가 **−19.30%** 를 냈다 — 자도 축도 아니고 **라우트를 만드는 방법**이
> 바뀐 것이다. 목표를 겨냥한 변경이 아니었다는 것이 이 항목의 기록이다. (그 뒤 4-19 가 `peak` 을
> 수렴시키면서 이 수는 **−18.20%** 가 됐다 — 같은 변경이 `large` 를 조금 비싸게 만들었고, 그
> 교환은 [`phase4-endpoint-merges.md`](docs/benchmarks/phase4-endpoint-merges.md) §5 에 적혀 있다.)
> 기본 전략이 되지 못한 이유는 위 벤치마크 문단에 있다.

> **열화는 사다리이고, 각 단이 무엇을 포기하는지 수치로 말한다** ([`phase4-fast-mode.md`](docs/benchmarks/phase4-fast-mode.md), ADR-034).
> 두 조건은 다른 것을 뜻하므로 처방도 다르다 — **직전 계획이 예산의 80%를 썼다**는 「개선이
> 예산을 다 썼다」이므로 다음 계획의 **개선 예산을 절반으로**, **파티션 컨슈머 랙 > 3 웨이브**는
> 「처리량이 모자란다」이므로 §6.5 5단계를 아예 **끈다**(FAST). 전략을 바꾸는 것이 아니다.
> **두 단의 대가는 4-5 가 전 데이터셋으로 다시 쟀다**
> ([`phase4-strategies.md`](docs/benchmarks/phase4-strategies.md) §4).
> 아랫단은 ADR-039·040·041 이 개선 단계의 일을 줄여서 **5초 예산에서는 조건이 아예 켜지지
> 않는다**(3,453 ms = 예산의 69%) — 켜지는 첫 예산은 **4초**이고 그때의 대가는 **+1.06%** 다.
> 윗단(FAST)의 대가는 `large` **+13.08%** 인데, **전략마다 다르다**: 같은 자리에서
> `savings-cw+ls` 는 **+4.29%** 다. **개선 단계에 기대는 정도가 곧 FAST 의 대가다.**
> 아낀 1 ms 당으로 보면 아랫단 **67원** 대 윗단 **401원** — **사다리의 순서는 그대로다.**
> 처음에는 둘 다 FAST 를 냈는데 **45배 비싼 처방**이었고(+0.21% 대 +9.4%), 그것을 뒤집은 것은
> 우리가 낸 표였다. §6.7 의 「fast mode ≤ 5초」는 **다섯 데이터셋 전부 통과**한다(가장 큰 `peak`
> 이 4,285 ms).
> **그리고 다섯 데이터셋 전부에서 `sweep-greedy-nn+ls` 의 FAST 결과가 `sweep-greedy-nn` 의 FULL 과
> 한 자리도 다르지 않다** — 「FAST 는 5단계를 끈 같은 전략」이라는 문장의 다섯 번째 확인이다.
> **왜 열화했는지는 계획 행에 남고**(`route_plans.mode_reason`), 운영자가 지정한 FAST 는
> 열화로 세지 않는다 — 사람이 고른 것은 시스템이 밀려서 포기한 것이 아니다.

상세: [DESIGN.md §6](docs/DESIGN.md)

---

## 기술 스택

애플리케이션 버전은 `gradle/libs.versions.toml`, 인프라 이미지 버전은 `deploy/compose/.env` **한 곳에서만** 고정한다.
아래 표는 2026-08-29 기준으로 실제 확인한 버전이다.

| 계층 | 선택 | 버전 |
|---|---|---|
| 언어·런타임 | Java (Eclipse Temurin) | **25 LTS** (25.0.4.1, Gradle이 자동 프로비저닝 → [ADR-014](docs/adr/ADR-014-jdk25-toolchain-auto-provisioning.md)) |
| 빌드 | Gradle (Kotlin DSL) | 9.3.0 wrapper, 설정 캐시·병렬 빌드 ON |
| 프레임워크 | Spring Boot | **4.1.1** (Spring Framework 7.0.9, Spring Kafka 4.1.1, Spring Security 7.1.1) |
| ORM·마이그레이션 | Hibernate ORM 7.4.5 / Flyway 12.4.0 | Boot BOM 관리, `ddl-auto=validate` |
| 직렬화 | Jackson **3** (`tools.jackson.*`) | Boot 4의 기본. Boot 3의 `com.fasterxml.jackson.*` 이 아니다 |
| 메시징 | Apache Kafka (KRaft) | `apache/kafka:4.3.1` |
| RDB | PostgreSQL | `postgres:18.2` — 서비스별 DB |
| 캐시·조정 | Redis | `redis:8.8.2` — GEO·Lua·NX 락 |
| 관측성 | Micrometer 1.17.1 + OpenTelemetry 1.62.0 | `prom/prometheus:v3.14.0`, `grafana/grafana:13.1.0`, `grafana/tempo:2.9.5`, `otel/opentelemetry-collector-contrib:0.159.0` |
| 테스트 | JUnit Jupiter 6.0.3, AssertJ, Testcontainers 2.0.5, ArchUnit 1.5.0, k6 | `integrationTest` 소스셋 분리 |
| 컨테이너 이미지 | Spring Boot Buildpacks (`bootBuildImage`) | → [ADR-013](docs/adr/ADR-013-container-image-buildpacks.md) |
| 프론트 (계획) | React 19 + Vite + TypeScript + Leaflet | Phase 6 |

컴파일은 `-parameters -Xlint:all,-serial,-processing,-this-escape -Werror` 로 돈다. **경고 하나도 허용하지 않는다.**

---

## 저장소 구조

```
dawnline/
├── docs/
│   ├── DESIGN.md                 # 진실의 원천 (설계서)
│   ├── IMPLEMENTATION_PLAN.md    # Phase별 작업 지시와 DoD
│   ├── adr/                      # 아키텍처 결정 기록
│   ├── benchmarks/               # 전략 비교·피크 측정 리포트 (Phase 3~)
│   ├── runbooks/                 # 운영 런북 (Phase 7)
│   └── postmortems/              # 피크 시뮬레이션 포스트모템 (Phase 7)
├── contracts/
│   ├── events/                   # 이벤트 JSON Schema + examples
│   └── openapi/                  # 서비스별 OpenAPI (빌드 산출물 커밋)
├── libs/
│   ├── common/                   # UUIDv7, GeoPoint, Geohash, Money(KRW), TimeWindow, 도메인 예외
│   ├── messaging/                # EventEnvelope, Outbox 릴레이, IdempotentConsumer, Kafka 설정
│   └── observability/            # 메트릭 명명, MDC 필터, JSON 로그, OTel 설정
├── services/
│   ├── order-service/            # 주문 접수·취소·멱등
│   ├── fulfillment-service/      # FC 선택, 캠프/권역, 웨이브·컷오프
│   ├── dispatch-service/         # 룰 엔진 + 최적화 + 라우트  ← domain/optimizer 가 핵심
│   ├── tracking-service/         # 배송 진행·ETA·지연 위험
│   └── ops-api/                  # CQRS 읽기 모델, 운영자 커맨드
├── apps/ops-web/                 # 운영 콘솔 (React, Phase 6)
├── tools/{sim-runner,benchmark}/ # 부하·기사 시뮬레이터 / 전략 벤치마크 하네스
├── deploy/{compose,k8s}/         # 로컬 전체 스택 / (선택) 매니페스트
├── buildSrc/                     # dawnline.java-conventions, dawnline.spring-service
└── gradle/libs.versions.toml     # 애플리케이션 의존성 버전의 유일한 고정 지점
```

---

## 빠른 시작

**전제조건**

- Git
- Docker (Compose v2) — 로컬 스택, Testcontainers 통합 테스트, 이미지 빌드에 필요
- **JDK 설치는 필요 없다.** Gradle wrapper가 Temurin 25를 자동으로 내려받는다
  ([ADR-014](docs/adr/ADR-014-jdk25-toolchain-auto-provisioning.md)).
  폐쇄망이라 자동 다운로드가 불가능하면 JDK 25를 설치한 뒤
  `org.gradle.java.installations.paths=<경로>` 를 `gradle.properties` 에 지정한다.

```bash
git clone <repo> && cd dawnline

./gradlew build          # 컴파일 + 단위 + ArchUnit + 계약 테스트 + 커버리지 게이트
./gradlew integrationTest # Testcontainers 통합 테스트 (Docker 필요)

make up                  # 로컬 전체 스택 기동 (PostgreSQL · Kafka · Redis · 관측성 · 서비스 5개)
make down                # 종료
```

**Phase 0 완료 시 확인할 수 있는 것** (= Phase 0의 DoD)

- `./gradlew build` 통과, ArchUnit 테스트 존재·통과
- 서비스 5개의 `/actuator/health/readiness` 가 200
- Kafka 토픽 목록에 [DESIGN.md §4.1](docs/DESIGN.md)의 토픽 전체가 존재
- `libs/messaging` 통합 테스트: outbox INSERT → 릴레이 → Kafka 수신 → 같은 이벤트 2회 전달 시 1회만 처리

**아직 동작하지 않는 것** — `make demo`(주문 시드 + smoke 시나리오), `make peak`, `make chaos-*` 는
각각 Phase 1–2, Phase 7에서 의미를 갖는다. Phase 0의 서비스는 헬스 체크만 노출하는 빈 껍데기다.

---

## 현재 구현 현황

| Phase | 내용 | 상태 |
|---|---|---|
| 0 | 모노레포·컨벤션 플러그인, `libs/*`, Compose 스택, ArchUnit, CI 골격, 이벤트 계약, ADR | ✅ 완료 |
| 1 | `order-service` — 주문 접수·취소, 멱등 POST, Outbox 발행, k6 부하 측정 | ✅ 완료 |
| 2 | `fulfillment-service` — FC 선택, 권역, 웨이브·컷오프 스케줄러(분산 락) | ✅ 완료 |
| 3 | `dispatch-service` 코어 — 룰 엔진, 비용 모델, `sweep-greedy-nn`, 설명, 벤치마크 하네스 | ✅ 완료 |
| **4** | 최적화 고도화 — Local Search, `savings-cw+ls`, FAST 열화 모드, 전략 비교 리포트 | ✅ 완료 |
| 5 | `tracking-service` + 기사 시뮬레이터 + 지연 위험 감지·부분 재계획 | 🔨 다음 |
| 6 | 백오피스 — `ops-api` 읽기 모델·커맨드, `ops-web` 대시보드·라우트 지도 | ⬜ 예정 |
| 7 | 신뢰성·관측성 마감 — Grafana 대시보드, 카오스 스크립트, 피크 측정, 런북·포스트모템 | ⬜ 예정 |

Phase 3까지가 데모 가능한 MVP다. 각 Phase의 작업 목록과 완료 기준(DoD)은
[IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md)에 있다.

### 측정해서 채울 자리

이 프로젝트는 "측정하지 않은 것은 주장하지 않는다"를 규칙으로 삼는다. 아래는 아직 **비어 있는** 항목이다.

| 항목 | 채우는 시점 |
|---|---|
| ~~주문 API p50/p95/p99, 오류율 (k6, 500 rps)~~ | ✅ [phase1-orders-k6.md](docs/benchmarks/phase1-orders-k6.md) (2026-09-05) |
| ~~전략별 총비용·계획 시간 비교표~~ | ✅ [phase4-strategies.md](docs/benchmarks/phase4-strategies.md) (2026-09-18, 다섯 데이터셋 × 네 전략 + 사다리 두 단). 첫 표는 [phase3-baseline.md](docs/benchmarks/phase3-baseline.md) (2026-09-05) |
| 피크 시나리오 실측 vs SLO 표 | Phase 7 → `docs/benchmarks/` |
| 카오스 검증 결과 (Kafka/Redis 중단, 인스턴스 강제 종료) | Phase 7 |
| 아키텍처 다이어그램 이미지, 데모 GIF, Tempo 트레이스 스크린샷 | Phase 7 |
| 정시 배송률 (지연 주입 시뮬레이션) | Phase 5 이후 측정, Phase 7 리포트 |

---

## 문서

| 문서 | 내용 |
|---|---|
| [docs/DESIGN.md](docs/DESIGN.md) | 설계서. 이 저장소의 진실의 원천. 도메인·이벤트·서비스·최적화 엔진·SLO |
| [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) | Phase별 작업 지시와 완료 기준(DoD) |
| [docs/adr/](docs/adr/README.md) | 아키텍처 결정 기록 — 무엇을 왜 택했고 무엇을 왜 버렸는가 |
| [CLAUDE.md](CLAUDE.md) | 이 저장소의 불변 규칙 12개 (아키텍처·코딩 컨벤션·작업 방식) |

**먼저 읽으면 좋은 ADR**

- [ADR-006 — at-least-once + 멱등 소비자](docs/adr/ADR-006-at-least-once-idempotent-consumer.md):
  Kafka의 "exactly-once"로는 왜 DB 쓰기와의 원자성을 얻을 수 없는지.
- [ADR-007 — 헥사고날 + 도메인/JPA 분리](docs/adr/ADR-007-hexagonal-architecture-archunit.md):
  매퍼 보일러플레이트라는 실제 비용을 지불하고 무엇을 사는지.
- [ADR-002 — DB-per-service + 폴링 Outbox](docs/adr/ADR-002-db-per-service-polling-outbox.md):
  Debezium CDC를 왜 지금은 쓰지 않는지, 그리고 어떻게 CDC로 가는 길을 막지 않았는지.
