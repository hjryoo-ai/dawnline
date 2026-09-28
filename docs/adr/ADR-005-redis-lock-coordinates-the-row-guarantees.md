# ADR-005 — Redis 락은 조정하고, 보장은 DB 행이 한다

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (2026-09-28 — Phase 2 에 계획했고 쓰지 않았던 ADR 을 **구현된 사실에서** 쓴다) |
| 결정일 | 2026-09-28 (구현은 Phase 1–2, 2026-09-03 ~ 09-05) |
| 관련 문서 | `docs/DESIGN.md` §5.2(컷오프 스케줄러) · §7.1(락 규칙) · §7.2(Redis 키 표) · §8.4(장애 모드) |
| 관련 ADR | [ADR-018](ADR-018-idempotency-lock-in-redis-record-in-db.md) (멱등 — 같은 모양의 첫 사례) · [ADR-025](ADR-025-wave-admission-share-lock.md) (웨이브 행 잠금) · [ADR-027](ADR-027-outbox-relay-leader-lock.md) (릴레이 리더는 Redis 를 떠났다 — 이 ADR 의 기각 사유에 각주를 붙였다) |

---

## 맥락

처음 표의 제목은 「Redis `SET NX` 락 + DB 낙관적 락 이중화」였다. 쓰이지 않은 채 Phase 2 가 지나갔고, 그 사이 락이 넷 생겼고 하나가 떠났다.
이 ADR 은 계획의 문장이 아니라 **그 넷이 실제로 무엇에 기대는지**를 적는다. 규칙은 불변규칙 7 이다 — Redis 는 진실 저장소가 아니다.

| 락 | 자리 | Redis 가 없을 때 | 정확성의 근거 |
|---|---|---|---|
| `lock:wave:{id}` — `SET NX PX 60000`, Lua 비교 삭제 | fulfillment 컷오프 스케줄러(`RedisWaveLock`) | **fail-open** — 진행하고 `servedByFallback("wave_lock")` 로 센다 | `SELECT version FROM waves … FOR UPDATE` + `OPEN` 상태 확인 + `@Version` (ADR-025) |
| `idem:order:{key}` — `IN_PROGRESS` NX 30초 · `DONE` 24시간 | order 접수(`RedisIdempotencyCache`) | `UNAVAILABLE` 로 진행, 장애 게이트가 10초 동안 Redis 를 건너뛴다 | `idempotency_keys` PK + `ON CONFLICT DO NOTHING` (ADR-018) |
| `route:{id}:atrisk:cooldown` — NX 5분 | tracking at-risk 발행 | fail-open, `dawnline_at_risk_cooldown_bypassed_total` | 정확성이 아니라 **소음**의 락이다 — 이중 재계획은 dispatch 의 `routes.last_replanned_at` 가 막는다(ADR-046) |
| ~~릴레이 리더~~ | ~~outbox 릴레이~~ | — | **Redis 를 떠났다** — `pg_try_advisory_lock`(ADR-027 후속 정정). fail-open 이 틀린 유일한 락이었다 |
| ~~`lock:plan:{waveId}`~~ | ~~dispatch~~ | — | 2026-09-05 에 지웠다 — `route_plans.wave_id UNIQUE` 가 이미 같은 일을 한다 |

## 결정

1. **Redis 락은 조정자다 — 같은 일을 두 인스턴스가 동시에 시작하지 않게 할 뿐, 틀린 결과를 막는 것은 DB 행이다.** 그래서 Redis 락은
   **fail-open** 이다: 없으면 진행하고, 그 사실을 센다. 막는 쪽(fail-closed)은 Redis 장애를 서비스 장애로 바꾼다 — 컷오프에 웨이브가 닫히지
   않는 것이 이중 실행보다 비싸다.
2. **보장의 모양은 자리마다 고른다 — 「낙관적 락」 하나로 부르지 않는다.** 웨이브 닫기는 비관적 잠금(`FOR UPDATE`)과 상태 확인이다 —
   편입이 웨이브 행을 읽기만 하므로 버전 비교로는 닫히지 않았다(ADR-025). 멱등은 PK 다. `@Version` 은 상태 전이가 있는 애그리거트 전부에
   있고 **넷째 방어선**이다 — `WaveLockedReadIT` 가 영속성 컨텍스트의 낡은 사본을 `@Version` 이 잡은 것을 기록했다(2026-09-26).
3. **락이 fail-open 이면 안 되는 자리는 Redis 에 두지 않는다.** 릴레이 리더가 그 경우였고(둘이 동시에 리더면 순서가 깨진다), advisory lock 으로
   옮겼다. **서비스 <em>간</em> 락은 이 시스템에 하나도 없다** — advisory lock 을 기각한 원래 사유(서비스마다 DB 가 다르다)는 서비스 간 락에만
   해당한다(ADR-027 의 각주).

## 결과

- 정정 둘(설계서의 낡은 문장): §7.2 `lock:wave` 의 폴백 「단일 인스턴스 가정 하 DB 낙관적 락」 → **「`FOR UPDATE` + `OPEN` 상태 확인(+ `@Version`) — 인스턴스 수와 무관하다」**,
  §8.4 「Redis 락 + 낙관적 락」 → 같은 문장.
- 카오스 검증: `make chaos-redis` 에서 발행 지연 최대 0.110초 · 검증 표 전부 ✅(RB-03) — 락 셋이 fail-open 으로 지나갔다(근거: 관측).

## 대안

| 안 | 기각 사유 |
|---|---|
| Redisson(`RLock` · 펜싱 토큰) | 조정자에 펜싱은 과하다 — 정확성은 DB 행이 이미 준다. 의존 하나가 늘어난다 |
| Redis 락 fail-closed | Redis 장애가 컷오프 장애가 된다 |
| DB 락만(Redis 없이) | 가능하다 — 지금도 Redis 없이 정확하다. Redis 가 덜어 주는 것은 경합의 비용(대기 · 재시도)이고, 그 비용은 인스턴스가 늘 때 커진다 |

## 재검토 지점 (포트폴리오 범위 밖 — 다시 여는 조건)

- **인스턴스가 실제로 둘 이상 뜰 때** — `servedByFallback("wave_lock")` 가 0 이 아니고 `FOR UPDATE` 대기가 컷오프 지연으로 보이면 fail-open 의
  비용을 잰다(ADR-027 재검토 A16 과 같은 날).
