# 아키텍처 결정 기록 (ADR)

이 디렉터리는 Dawnline의 설계 결정을 기록한다. 결정 목록의 원본은 `docs/DESIGN.md` §16이며,
각 ADR은 **맥락 → 결정 → 근거 → 고려한 대안과 기각 이유 → 결과(장점·비용·되돌리는 방법)** 형식을 따른다.

원칙은 네 가지다.

1. **설계서가 먼저다.** 설계를 바꾸려면 `docs/DESIGN.md` 를 수정하고 여기에 ADR을 추가한 뒤 코드를 고친다(`CLAUDE.md`).
2. **기각한 대안을 반드시 적는다.** 무엇을 택했는지보다 무엇을 왜 버렸는지가 결정의 내용이다.
3. **비용을 숨기지 않는다.** 각 ADR의 "결과"에는 이 선택이 청구하는 대가와, 되돌리는 방법이 적혀 있다.
4. **근거가 결함을 들면 그 결함을 어떻게 알았는지 표기한다** (2026-09-19 추가).
   「이렇게 하지 않으면 X 가 깨진다」는 문장 옆에 셋 중 하나를 적는다:
   **`근거: 관측(재현됨)`** · **`근거: 추정`** · **`근거: 재현 시도했으나 실패`**.
   선호나 앞일의 계획을 적은 문단에는 붙이지 않는다 — 대상은 *결함 주장*이다.

   이 줄이 생긴 이유: [ADR-009](ADR-009-url-path-api-versioning.md) 결정 3 의 음성 표본
   (`/actuator/health` 의 세그먼트를 버전으로 파싱해 프로브가 깨진다)이 Boot 4.1.x 에서
   **재현되지 않았고**, 그때 「결정 시점에 이 결함을 실제로 재현했는지」가 ADR 어디에도 적혀
   있지 않았다. 표기가 없으면 다음 사람은 **「측정했는데 환경이 바뀐 것」과 「처음부터 추정이었던
   것」을 구별할 수 없다** — 그리고 그 둘은 결정을 되돌릴지 판단할 때 정반대의 값을 갖는다.
   빈칸으로 두지 않는다: 재지 않았으면 `추정` 이라고 적는 것이 이 표기의 요점이다.

## 목록

| ADR | 결정 | 상태 | 문서 |
|---|---|---|---|
| 001 | Gradle 멀티모듈 모노레포 | ✅ Accepted (2026-08-29) | [ADR-001](ADR-001-gradle-multi-module-monorepo.md) |
| 002 | DB-per-service + 폴링 Outbox 릴레이 | ✅ Accepted (2026-08-29) | [ADR-002](ADR-002-db-per-service-polling-outbox.md) |
| 003 | JSON + JSON Schema 이벤트 계약 | ✅ Accepted (2026-08-29) | [ADR-003](ADR-003-json-schema-event-contracts.md) |
| 004 | **비교 대상은 다른 솔버가 아니라 불가능의 경계다** — `timefold` 는 등록하지 않고, 다시 여는 조건 셋을 적는다 | ✅ Accepted (2026-09-18) | [ADR-004](ADR-004-compare-against-the-boundary-not-another-solver.md) |
| 005 | Redis `SET NX` 락 + DB 낙관적 락 이중화 | ⏳ Phase 2 예정 | — (advisory lock 기각 사유에 [ADR-027 후속 정정](ADR-027-outbox-relay-leader-lock.md)의 각주가 붙었다 — **서비스 <em>간</em> 락에만 해당한다**) |
| 006 | at-least-once + 멱등 소비자 (Kafka EOS 미사용) | ✅ Accepted (2026-08-29) | [ADR-006](ADR-006-at-least-once-idempotent-consumer.md) |
| 007 | 헥사고날 + ArchUnit 강제, 도메인/JPA 엔티티 분리 | ✅ Accepted (2026-08-29) | [ADR-007](ADR-007-hexagonal-architecture-archunit.md) |
| 008 | 가상 스레드(I/O) + ForkJoin(CPU) 분리 | ⏳ Phase 4 예정 | — |
| 009 | URL 경로 API 버저닝(v1) | ✅ Accepted (2026-09-03) | [ADR-009](ADR-009-url-path-api-versioning.md) |
| 010 | 하버사인 × 도로계수 기본, OSRM 어댑터는 선택 | ⏳ Phase 3 예정 | — |
| 011 | 롤링 배포 시 소비자 static membership | ⏳ Phase 7 예정 | — |
| 012 | CQRS 읽기 모델을 ops-api에 집중 | ⏳ Phase 6 예정 | — |
| 013 | 컨테이너 이미지 = Spring Boot Buildpacks(`bootBuildImage`) | ✅ Accepted (2026-08-29) | [ADR-013](ADR-013-container-image-buildpacks.md) |
| 014 | JDK 25 툴체인 자동 프로비저닝 (foojay-resolver) | ✅ Accepted (2026-08-29) | [ADR-014](ADR-014-jdk25-toolchain-auto-provisioning.md) |
| 015 | Outbox 발행 실패를 결정적/일시적으로 나누고 결정적 실패만 격리 | ✅ Accepted (2026-09-01) | [ADR-015](ADR-015-outbox-publish-side-quarantine.md) |
| 016 | 레디니스에서 Kafka 브로커 연결 제외 | ✅ Accepted (2026-09-01) | [ADR-016](ADR-016-readiness-excludes-kafka.md) |
| 017 | 주문 상태 머신이 순서 뒤바뀜을 흡수 (`PLANNED → DELIVERED` + 진행 단계 비교) | ✅ Accepted (2026-09-02) | [ADR-017](ADR-017-order-state-machine-absorbs-out-of-order-events.md) |
| 018 | 멱등 잠금은 Redis 키(PX 30000), DB 에는 `DONE` 만 기록 | ✅ Accepted (2026-09-03) | [ADR-018](ADR-018-idempotency-lock-in-redis-record-in-db.md) |
| 019 | 멱등 기록 보존 7일, `status` 컬럼 제거 | ✅ Accepted (2026-09-03) | [ADR-019](ADR-019-idempotency-record-retention-7-days.md) |
| 020 | 컷오프 계산은 order-service 한 곳 + 웨이브 마감 grace + 약속 개정 | ✅ Accepted (2026-09-05) | [ADR-020](ADR-020-cutoff-ownership-wave-grace-promise-revision.md) |
| 021 | 권역 시드를 order-service 지오코더의 출력에서 파생 (권역 91개) | ✅ Accepted (2026-09-05) | [ADR-021](ADR-021-zone-seed-derived-from-geocoder.md) |
| 022 | fulfillment 주문 단위 애그리거트 `fulfillment_orders`, `wave_orders` 드롭 | ✅ Accepted (2026-09-05) | [ADR-022](ADR-022-fulfillment-order-aggregate.md) |
| 023 | `fulfillment_orders` 30일 · `waves` 90일, 파티션 대신 배치 삭제 | ✅ Accepted (2026-09-05) | [ADR-023](ADR-023-fulfillment-retention.md) |
| 024 | 웨이브 계획 완료는 `plan.completed.v1` 이 알린다 (`route.assigned` 가 아니라) | ✅ Accepted (2026-09-05) | [ADR-024](ADR-024-plan-completed-event.md) |
| 025 | 웨이브 편입은 `FOR SHARE`, 마감만 `FOR UPDATE`. `order_count` 는 마감 시 집계 | ✅ Accepted (2026-09-05) | [ADR-025](ADR-025-wave-admission-share-lock.md) |
| 026 | 취소는 최적화 트리거가 아니라 입력 변경 — stop 을 죽이고 시간만 재전파한다 | ✅ Accepted (2026-09-05) | [ADR-026](ADR-026-dispatch-cancellation-window.md) |
| 031 | 배정 동률은 「능력이 적은 차 먼저」 — id 순서는 결정이 아니라 우연이었다 | ✅ Accepted (2026-09-08) | [ADR-031](ADR-031-least-capable-first-tie-break.md) |
| 038 | **고정비 하한은 총비용의 하한이 아니다** — 리포트가 불가능의 경계를 함께 싣는다. 하한까지 밀면 빈 좌석이 사라지고 희소 능력 수요가 앉을 자리를 잃는다 | ✅ Accepted (2026-09-12) | [ADR-038](ADR-038-fixed-cost-floor-is-not-a-total-cost-floor.md) |
| 037 | **재삽입은 들어갈 수 없는 자리를 시도하지 않는다** — 위치 무관 하드 룰로 라우트를 먼저 거른다(상한 96.8%). 정확한 변경이라 결과가 한 자리도 안 바뀐다 | ✅ Accepted (2026-09-12) | [ADR-037](ADR-037-reinsertion-prunes-what-cannot-fit.md) |
| 036 | **마감은 계획 전체의 것이다** — 배정·재삽입·개선이 같은 마감 아래. 이것이 없으면 열화 사다리가 허구다 | ✅ Accepted (2026-09-12) | [ADR-036](ADR-036-deadline-belongs-to-the-plan.md) |
| 035 | 병렬 단위는 **클러스터가 아니다** — 배정은 순차 의존, 개선의 88%는 라우트 쌍. 그리고 **벽시계가 물린 실행은 재현 대상이 아니다** | ✅ Accepted (2026-09-11) | [ADR-035](ADR-035-parallel-unit-is-not-the-cluster.md) |
| 034 | 열화는 **사다리다** — 랙만 FAST, 예산 조건은 개선 예산 절반. 왜 열화했는지가 계획 행에 남는다 | ✅ Accepted + 후속 정정 (2026-09-10) | [ADR-034](ADR-034-degrade-mode.md) |
| 033 | 겹친 제약은 한 대에 몰리지 않는다 — 기준 80% · 능력 분포 · 통합 키 | ✅ Accepted (2026-09-09) | [ADR-033](ADR-033-constraint-classes.md) |
| 032 | 국소 탐색은 근사로 후보를 줄이고, 예산은 패스 단위로만 끊는다 | ✅ Accepted (2026-09-09) | [ADR-032](ADR-032-local-search-budget-and-approximations.md) |
| 030 | 부록 A 에 야간 근무조(캠프당 8대 23:00–08:00) — 「새벽 배송」에 새벽 차량이 없었다 | ✅ Accepted (2026-09-08) | [ADR-030](ADR-030-night-shift-seed.md) |
| 029 | 최적화기 I/O 경로(입력 적재·결과 저장)는 ORM 이 아니라 벌크 | ✅ Accepted (2026-09-08) | [ADR-029](ADR-029-optimizer-io-is-bulk-not-orm.md) |
| 028 | 미배정 정책 — 우선도는 파생이고, 자리는 비싼 것부터 준다 | ✅ Accepted (2026-09-09) | [ADR-028](ADR-028-unassigned-policy.md) |
| 027 | outbox 릴레이는 리더 락으로 단일 활성, 리더를 모르면 발행하지 않는다 | ✅ Accepted (2026-09-05) + **후속 정정 (2026-09-05)** — 조정자를 Redis → PostgreSQL advisory lock | [ADR-027](ADR-027-outbox-relay-leader-lock.md) |
| 052 | **위임 클라이언트는 커밋된 계약에서 만든다 — 채택 기준을 먼저 적는다** — 후보 하나(`spring` 생성기 · `spring-http-interface`), 기준 다섯(표준 템플릿 · 문서화된 옵션만 · 생성물 그대로 컴파일 · Jackson 3 왕복 · 새 런타임 의존 없음) — 하나라도 거짓이면 손으로 쓴 인터페이스 + YAML 대조 테스트 · 토큰은 스크립트가 찍고 ops-api 는 검증만 · 감사 행은 위임 **전에** `PENDING`, 응답을 못 받으면 `UNKNOWN` · 감사 id 를 상관 헤더로 | ⏳ Proposed (2026-09-24) | [ADR-052](ADR-052-delegation-client-is-generated-from-the-committed-contract.md) |
| 051 | **읽기 모델의 행은 먼저 온 사실이 만든다 — 부재는 값이 아니다** — 축 규칙([ADR-017](ADR-017-order-state-machine-absorbs-out-of-order-events.md))의 **다섯 번째 자리**이고, 앞의 넷과 달리 **행 하나에 여러 토픽이 쓴다**(`rm_orders` 에 여섯 — 2026-09-24 DDL 정정 뒤 일곱 · `rm_waves` 에 넷 · `rm_routes` 에 넷) — 그래서 「이 전이를 받는가」 앞에 **「그 행이 아직 있기는 한가」**가 하나 더 있다 · 핸들러는 전부 **upsert** 이고 「행을 만드는 핸들러」를 두지 않는다(늦게 온 `UPDATE` 는 0 행을 갱신하고 **예외 없이 성공**한다) · **자기 칸만 쓴다** — 모르는 칸에 `NULL`·`0`·`false` 를 넣지 않는다(`false` 는 「위험하지 않다」라는, 아직 아무도 하지 않은 주장이다) · 개수는 증감이 아니라 **집계**다([ADR-025](ADR-025-wave-admission-share-lock.md) 의 「카운터 드리프트가 구조적으로 불가능」과 같은 형태 — `delivery.status` 가 `order.dispatched` 보다 먼저 오면 올릴 라우트가 없다) · 「아직 안 왔다」는 DLQ 도 `rejected` 도 아니다(§4.6) · 관측 근거는 **순서를 뒤섞는 IT** 이고 토픽을 **빼는 방식**으로 돈다([ADR-050](ADR-050-route-departure-is-an-event.md) 이 방금 열한 번째를 더했다 — 열거였다면 그 토픽은 검사 밖이었다) · 근거는 **관측(재현됨)**(2026-09-24 — 기각한 반대안 셋을 임시로 넣자 셋 다 씨 1 에서 사실을 조용히 잃었다) | ✅ Accepted (2026-09-23) | [ADR-051](ADR-051-first-fact-creates-the-row-absence-is-not-a-value.md) |
| 050 | **라우트 출발은 이벤트다 — 출발이 첫 편차의 출처이기 때문이다** — `dawnline.delivery.route-departed.v1`(키 `routeId`, 소비자 **ops 뿐**) · 근거는 화면이 아니라 **사실의 가시성**이다: 지금 출발을 아는 것은 tracking 뿐이라(`ScanType.isPublished()`) ops 는 첫 `ARRIVED` 까지 「출발 안 함」과 「출발했는데 아직 도착 없음」을 구별하지 못하고, **그 구간이 운영자가 개입할 수 있는 마지막 창**이다 · **라우트 하나에 이벤트 하나** — 반복하지 않는 이유가 말하지 않을 이유였던 적은 없다(ADR-024 의 거울상: 사실의 단위와 토픽의 단위를 맞춘다) · 페이로드 여섯 칸은 **마이그레이션 없이** 나온다(`route_revisions` 셋 · 스캔의 `occurredAt` · `shipments` 의 `COUNT(DISTINCT stop_seq)`) · 스키마·예시·토픽·발행은 **소비자가 먼저**(묶음 B) | ✅ Accepted (2026-09-23) | [ADR-050](ADR-050-route-departure-is-an-event.md) |
| 049 | **Spring 을 아는 공유 코드는 자기 lib 에 산다, common 은 순수하게 남는다** — 세 서비스의 `ProblemDetailsAdvice` 를 **새 모듈 `libs/web`** 으로 뽑는다(갈라지던 칸은 `RETRY_AFTER_SECONDS` 하나) · 훅은 **추상 메서드**다 — 기본값을 주면 「그런 오류가 없다」는 판단이 코드에서 사라진다 · ArchUnit 규칙 9(`@ControllerAdvice` 계열은 전부 이 기반을 쓴다, **열거가 아니라 조건**) · 규칙 10(`libs/common` 의 main 은 Spring·JPA 비의존 — build 파일의 주석은 문장이지 강제가 아니다) | ✅ Accepted (2026-09-23) | [ADR-049](ADR-049-spring-aware-shared-code-lives-in-its-own-lib.md) |
| 048 | **재계획은 자기 DB 로 푼다 — 페이로드는 트리거다** — 남은 stop 도 **편차도** `route_stops` 가 말한다(V10 `actual_at` = 그 stop 에 **처음 닿은** 시각, 덮어쓰지 않는다) · 페이로드의 `deviationSeconds` 는 입력이 아니라 **대조값**이고 갈리면 `dawnline_at_risk_deviation_mismatch_total`(허용 60초) · **편차는 평가 시계를 밀지 저장되는 `planned_arrival` 을 밀지 않는다**(밀면 다음 편차의 기준선이 사라진다) · 닿은 stop 이 없으면 편차는 **모름**이고 0 이 아니다(`no-anchor`) · `relocate` 세 조건: 현재 위치 이후만 · [ADR-039] 조합 게이트 · 두 라우트 모두 재검증·revision 증가 · 실패는 DLQ 가 아니라 `dawnline_replan_total{outcome}` · `applied` 는 `plan_explanations` 에 `AT_RISK_RELOCATE` 로 「어디서 어디로, Δ비용 얼마」를 남긴다 | ✅ Accepted (2026-09-23) | [ADR-048](ADR-048-replan-reads-its-own-db.md) |
| 047 | **배송 상태는 사실이고 개정은 계획이다** — **계획은 `(route, revision, seq)` 로, 사실은 `orderId` 로 식별한다**(스캔 API·tracking·dispatch 세 자리가 같은 열쇠를 쓴다 — 스캔 API 는 2026-09-23 에 `orderIds` 필수가 되었고 `DEPARTED_CAMP` 만 라우트의 사건이라 예외다) · `delivery.status` 는 stop 을 `seq` 로 찾지 않고 **`routeId` 로 좁히지도 않는다**(다른 라우트에서 찾으면 거기 적용 + `dawnline_status_after_relocate_total`, 어디에도 없을 때만 stale) · `route.assigned` 와 달리 **개정 번호로 거르지 않는다**(옛 사실을 버리면 §6.8 「미완료 stop 만」이 읽을 값이 사라진다) · `CANCELLED` 인 stop 의 상태는 무시하고 센다 · 이 전이가 §6.10 넷째 분기를 처음으로 발화 가능하게 한다 | ✅ Accepted (2026-09-22) | [ADR-047](ADR-047-delivery-status-is-a-fact-not-a-revision.md) |
| 046 | **at-risk 는 사건이고, 쿨다운은 알림 수를 지킨다** — 위험 해제는 알리지 않는다(재계획을 취소할 방법이 없다) · 쿨다운 둘의 집이 다르다: tracking=Redis/알림 수, dispatch=DB/정확성 — 멱등 소비자는 `eventId` 가 다르면 막지 못한다 | ✅ Accepted (2026-09-19) | [ADR-046](ADR-046-at-risk-is-an-event.md) |
| 045 | **개정 번호는 라우트의 것이다** — tracking 은 `route_revisions` 한 줄과 비교한다. `shipments` 에서 MAX 로 유도하는 안은 `relocate` 가 비운 라우트에서 무너진다 | ✅ Accepted (2026-09-19) | [ADR-045](ADR-045-revision-comparison-is-per-route.md) |
| 044 | **끝점은 전부 본다** — 근사는 stop 이 많을 때의 것이지 라우트가 적을 때의 것이 아니다 (`peak` 라우트 216 → 90) | ✅ Accepted (2026-09-17) | [ADR-044](ADR-044-endpoints-are-few-enough-to-see-all.md) |
| 043 | 기본 전략은 `peak` 이 수렴할 때까지 바꾸지 않는다 — 바꾸는 조건을 **미리** 적는다(벽시계가 아니라 구조) | ✅ Accepted (2026-09-17) | [ADR-043](ADR-043-default-strategy-stays-until-peak-converges.md) |
| 042 | savings 의 병합은 제약 조합을 안다 — 좌석 불변식을 구성 단계로 | ✅ Accepted (2026-09-17) | [ADR-042](ADR-042-savings-merges-are-class-aware.md) |
| 041 | 「차 한 대 몲」에는 stop 슬롯이 들어간다 (그리고 클러스터 수 상한은 남긴다) | ✅ Accepted (2026-09-12) | [ADR-041](ADR-041-cluster-target-counts-stop-slots.md) |
| 040 | `priority-boost` 는 순번이 아니라 **시각**으로 감쇠한다 (τ = 12분) | ✅ Accepted (2026-09-12) | [ADR-040](ADR-040-priority-boost-decays-in-time.md) |
| 039 | 좌석은 능력이 아니라 **제약 조합**에 예약한다 — 예약은 배정 단계의 것이라 미배정의 사유가 될 수 없다 | ✅ Accepted (2026-09-12) | [ADR-039](ADR-039-reserve-seats-by-constraint-class.md) |

- 이 표는 `docs/DESIGN.md` §16과 **같은 내용**이며 함께 갱신한다. 문서 열이 `—` 인 행은 아직 파일이 없다.
- **013·014는 §16 표에 없던 항목**으로, Phase 0 스캐폴딩 중에 확정되어 새로 추가했다.
- **015·016은 Phase 0 마감 감사에서** 드러난 결함·설계서 내부 모순을 확정한 것이다.
  015는 릴레이의 head-of-line blocking(실제 도달 가능한 결함), 016은 §8.6과 §8.4의 모순을 해소한다.
  **016 에는 Phase 2-4 에 후속 정정을 붙였다** — §8.6 이 레디니스 조건으로 남겨 둔 "(fulfillment)
  GEO 적재 완료" 가 같은 종류의 모순이었다. `geo:fc`·`geo:camp` 에는 §7.2 가 폴백을 정해 두었는데,
  적재 완료를 레디니스에 넣으면 Redis 장애가 곧 트래픽 차단이 되어 폴백을 만든 이유가 사라진다.
- **039–044 에는 이 표에 줄이 없었다 — 2026-09-19 에 채웠다.** 파일과 `docs/DESIGN.md` §16 은
  있었고 여기만 비어 있었다. 이 표가 §16 과 「같은 내용」이라고 적어 둔 이상, 빈 줄은 「아직
  없는 ADR」로 읽힐 수밖에 없다 — §13 의 「꺼 둔 검증은 실패하지 않는다」 와 같은 모양이다.
  045 를 넣으려다 번호를 세면서 드러났다.

- **004는 「도입하지 않는다」가 결정인 첫 ADR이다** (2026-09-18, Phase 4 마감). §16 의 원문은
  「자체 휴리스틱 기본 + **Timefold 비교**」였고, 비교는 「우리가 얼마나 먼가」에 답하기 위한
  외부 기준이었다. Phase 4 가 같은 물음에 다른 답 셋을 만들었다 — 고정비 하한 열(상시),
  그림자 계측 원장 여덟 줄, 구성 계열이 다른 두 전략 비교. **그래서 이 ADR 의 내용 절반은
  자기 한계다**: 그 열은 목적함수 다섯 항 중 하나만 덮는다. 기각을 «영원히» 로 적지 않기 위해
  **다시 여는 조건 셋**과 한정 실행(`medium` 한 개, Phase 7-6)을 함께 적었다.
- **021은 §16 표에 없던 항목**이다. 부록 A 의 "권역 60개" 가 order-service 지오코더의 출력을 덮지
  못한다는 것을 세어 보고(91개) 알게 되어 추가했다. 덮지 못하면 그 주소의 주문이 전부
  `UNSERVICEABLE` 이 되는데, 그것이 설계된 실패 경로와 구별되지 않는다.
- **027은 결정한 날 정정됐다.** 원 결정은 리더 락을 Redis `SET NX` 로 두고, 폴백이 없으니
  fail-closed 라고 §7.2·§13 에 **예외**를 만들었다. 리뷰가 그 앞 단계를 짚었다 — 릴레이 리더
  선출은 서비스 <em>내부</em> 조정이고 그 인스턴스들은 자기 outbox 가 있는 같은 DB 를 공유하므로,
  `pg_try_advisory_lock` 이면 TTL·갱신·시계가 없고 **DB 가 죽으면 발행할 것도 없으니 딜레마
  자체가 사라진다.** 즉 그것은 불변규칙 7 의 예외가 아니라 위반이었다. ADR-005 의 "advisory
  lock 은 서비스별 DB 분리 시 범위 한계" 라는 기각 사유는 **서비스 간 락**에만 해당하는데, 이
  시스템에는 서비스 간 락이 하나도 없다 — 일반 원칙을 개별 사례에 자동 적용한 것이 원인이었다.
  같은 정정에서 §5.3 의 `lock:plan` 도 설계에서 뺐다(`route_plans.wave_id` UNIQUE 가 이미 그
  안전장치다).
- **023은 022가 남긴 보존 문제를 닫는다.** 그 과정에서 ADR-020 의 지각 도착 경로에 상한이 없다는
  것도 드러나 **ADR-020 에 후속 정정**을 붙였다(ADR-002 와 같은 방식 — 원문을 고쳐 쓰지 않는다).
  20일 묵은 `order.placed` 가 DLQ replay 로 들어오면 "다음 웨이브 + 약속 개정" 을 타서 오늘 날짜의
  새 배송 약속이 나가는 문제였다.
- **022는 §16 표에 없던 항목**이다. 스키마를 구현하다 §5.2 의 `wave_orders` 가 주문에 대해
  fulfillment 가 아는 것의 절반만 담는다는 것이 드러났다 — `UNSERVICEABLE` 사유도, 약속 개정도,
  취소도 갈 곳이 없어 "주문 X 는 왜 웨이브에 없나" 에 답할 수 없었다.
- **025는 Phase 2-5 착수 전에** 설계자가 §5.2·§7.1 의 편입 락을 되짚어 확정했다. 편입에 배타
  락을 쓰면 §8.2 피크(컷오프 직전 600 rps 가 소수 웨이브에 몰림)에서 웨이브 행 하나가 처리량
  상한이 된다. 같은 검토에서 **ADR-020 에 후속 정정 2** 를 붙였다 — 약속 개정 경로에서
  fulfillment 가 "다음 웨이브" 의 컷오프를 알아야 하는데 ADR-020 에 그 답이 없었다.
- **024는 Phase 2-3 에서** `WaveStatus` 를 만들다 §5.2 의 웨이브 수명주기와 §4.1 의 소비자 표가
  어긋나 있는 것을 발견해 추가했다. `route.assigned` 는 라우트 단위라 웨이브의 계획 완료를 말할 수
  없고, 그 전이가 발화하지 않으면 **ADR-023 의 정리 배치가 `PLANNED` 주문 행을 영원히 지우지
  못한다**(보존 정책이 조용히 무한 보존이 된다). 같은 작업에서 ADR-017 의 "`CANCELLED` 축 밖
  판정이 정말 이상한 상황을 잡는다" 가 틀렸다는 것도 드러나 **ADR-017 에 후속 정정**을 붙였다 —
  그것은 이상 상황이 아니라 설계된 경합 창이고, 해소는 dispatch 가 소유한다(Phase 3 `[결정 필요] 9-2`).
- **020은 Phase 2 착수 시점에** 코드보다 먼저 확정했다. §5.2 가 "Phase 2 선결" 이라고 적어 둔 항목이며,
  구현하다 마주치면 "이미 나간 약속" 을 앞에 두고 급하게 정하게 되는 종류의 결정이기 때문이다.
- **017·018은 Phase 1 구현 중에** 설계서를 코드로 옮기다 드러난 미정·모순을 확정한 것이다.
  017은 §4.5가 "상태 머신으로 흡수"라고만 적고 방법을 정하지 않은 부분, 018은 §5.1 멱등 흐름의
  1단계(DB `IN_PROGRESS`)와 3단계(`DONE` INSERT)가 서로 맞지 않던 부분이다.

## `[결정 필요]` 해소 현황 (`docs/DESIGN.md` §17)

| # | 미결 항목 | 상태 |
|---|---|---|
| 1 | 도메인 모델과 JPA 엔티티 분리 여부 | ✅ **분리한다** — [ADR-007](ADR-007-hexagonal-architecture-archunit.md) |
| 2 | 고객 API 키 적용 여부 | ✅ **도입하지 않는다** — 고객 API 무인증 (`docs/DESIGN.md` §10). 남용 방지는 레이트 리밋이 맡되, 인증이 없으므로 그 키(`customerId`)는 클라이언트 주장값이다 |
| 3 | 이미지 빌드 Jib vs Buildpacks | ✅ **Buildpacks** — [ADR-013](ADR-013-container-image-buildpacks.md) (§14 본문도 갱신됨) |
| 4 | Redis vs Valkey | ⏳ 미결 (Redis 8로 진행, 라이선스 이슈 발생 시 재검토 — 명령 호환) |
| 5 | ops-web 지도 타일 | ⏳ 미결 (Phase 6에서 결정) |
| 6 | Timefold 실험 포함 여부 | ✅ **포함하지 않는다** — [ADR-004](ADR-004-compare-against-the-boundary-not-another-solver.md) (2026-09-18, Phase 4 마감). 비교 대신 §6.9 의 고정비 하한 열. Phase 7-6 여유 시 `medium` 한 개 한정 |

`[결정 필요]` 목록 밖에서 확정된 결정도 있다 — 설계서 내부 모순을 해소한 경우다.

| 출처 | 모순 | 확정 |
|---|---|---|
| §8.6 vs §8.4 | §8.6은 레디니스에 Kafka 프로듀서 초기화를 요구했으나 §8.4는 "브로커 다운 시 주문 API 정상"을 요구 | [ADR-016](ADR-016-readiness-excludes-kafka.md) — 레디니스에서 제외 |
| §4.6 | 소비 측 DLQ만 규정하고 발행 측 실패 정책이 없어, 독약 행이 릴레이를 영구히 막을 수 있었다 | [ADR-015](ADR-015-outbox-publish-side-quarantine.md) — 결정적 실패만 격리 |

## 새 ADR을 추가할 때

- 파일명: `ADR-<번호>-<영문-kebab-슬러그>.md`. 번호는 재사용하지 않는다.
- 파일이 있는 ADR의 상태는 `Accepted` / `Superseded by ADR-xxx` / `Deprecated` 중 하나로 유지한다.
  위 목록의 `⏳ Phase N 예정` 은 상태가 아니라 **아직 파일이 없다는 표시**다 — 파일을 만들 때
  `Accepted` 로 바뀐다.
  결정을 뒤집을 때는 기존 ADR을 **수정하지 말고** 새 ADR을 쓰고, 기존 문서의 상태만 `Superseded` 로 바꾼다.
- `docs/DESIGN.md` §16 표와 이 목록을 함께 갱신한다.
- 커밋 메시지는 `docs(adr): …` (Conventional Commits, `CLAUDE.md`).
