# ADR-069 — Redis 의 연결 예산은 명령 예산이 아니다 · 연결은 기동 때 미리 연다

| 항목 | 내용 |
|---|---|
| 상태 | Accepted (2026-09-28) |
| 결정일 | 2026-09-28 |
| 관련 문서 | `docs/DESIGN.md` §7.2 · §8.6 · `docs/benchmarks/phase7-window-scenarios.md` §3.1 · `docs/IMPLEMENTATION_PLAN.md` 7-0 A2 · D1 |
| 관련 ADR | [ADR-016](ADR-016-readiness-excludes-kafka.md) (레디니스는 DB 마이그레이션만 — Redis 선연결도 조건이 아니다) · [ADR-018](ADR-018-idempotency-lock-in-redis-record-in-db.md) (멱등 잠금 — 같은 연결을 쓴다) |

---

## 맥락

7-4 의 창 시나리오 네 실행 모두 창이 열리는 순간 `DawnlineRateLimitBypassed`(page)가 울렸다 — 우회 83 · 111 · 412 · 535건,
각 실행의 초당 주문 수 × 33–43초다. 같은 순간 fulfillment 의 `geo:fc` 조회도 DB 폴백을 탔고, `POST /orders` 의 최대 지연
3.1–3.5초가 전부 창의 첫 몇 초에 있었다.

overload-day 의 서비스 로그(유효 22:58 = 창 시작):

```
13:20:04Z RedisConnectionFailureException … Caused by StacklessClosedChannelException
13:20:14Z Connection initialization timed out after 50 millisecond(s)
13:20:37Z Command timed out after 50 ms
```

1. **기제 — 연결 수립이 명령 타임아웃 50 ms 안에 끝나야 했다.** 근거: 관측(재현됨) — 로그 위 줄, 그리고 이 ADR 의
   `RedisConnectTimeoutIT` 가 정정 전 설정에서 같은 메시지로 실패한다(검증 표).
   - Spring Data Redis 4.1.1 의 `LettuceConnectionFactory` 는 `RedisURI.timeout` 을 **`commandTimeout` 으로** 채운다(바이트코드로 확인).
   - Lettuce 7.5.2 의 `ConnectionBuilder` 는 그 값을 `RedisHandshakeHandler` 의 초기화 타임아웃으로 넘기고, `RedisClient.connect` 도
     그 값으로 연결을 기다린다(바이트코드로 확인).
   - 그래서 `dawnline.order.redis.command-timeout-ms=50` 은 **명령 하나의 예산이면서 TCP 연결 + HELLO 핸드셰이크의 예산**이었다.
2. **연결은 첫 요청이 연다.** Lettuce 의 공유 연결은 게으르고, order-service 는 기동 때 Redis 에 아무것도 하지 않는다(레디니스
   조건이 아니다 — ADR-016). 첫 연결은 창의 첫 주문이 핫패스에서 열고, 콜드 JVM 과 첫 요청이 몰리는 그 순간의 핸드셰이크가 50 ms 를
   넘는다. 실패하면 `RedisOutageGate` 가 10초 동안 Redis 를 건너뛰고, 10초 뒤 다시 핫패스에서 연결을 시도한다 — 세 번이면 30–40초다.

그 30–40초는 **레이트 리밋이 없던 시간**이다. 인증이 없는 API 에서 레이트 리밋은 유일한 남용 방지 수단이고(§10), 알림이 울린 것은
정확했다.

## 결정

1. **연결 예산과 명령 예산을 가른다.** 둘은 다른 질문이다 — 연결은 「서버에 닿는가」(초 단위), 명령은 「핫패스가 얼마까지 기다리는가」(ms 단위).
   - 연결: `RedisURI.timeout`(Spring 의 `commandTimeout` 자리)에 **연결 예산**을 준다 — order `dawnline.order.redis.connect-timeout-ms`,
     fulfillment `dawnline.fulfillment.redis.connect-timeout`, 기본 **2초**.
   - 명령: Lettuce 의 `TimeoutOptions` 로 **명령마다** 고정 타임아웃을 건다 — 기존 속성(order `command-timeout-ms` 50, fulfillment
     `command-timeout` 50ms) 그대로. Boot 는 이미 `TimeoutOptions.enabled()` 를 켜고 있었고(연결 예산을 명령 예산으로 쓰는 형태),
     `LettuceClientOptionsBuilderCustomizer` 로 그 값을 명시한다.
   - 한 설정 클래스(`RedisTimeoutConfig`)에 둘을 나란히 둔다 — 둘 중 하나만 고치는 날이 오지 않게.
2. **연결은 기동 때 미리 연다 — best-effort 이고 레디니스 조건이 아니다.** `ApplicationReadyEvent` 에서 별도 스레드가 공유 연결을
   열고 `PING` 한다. 실패하면 WARN 한 줄이고 기동은 그대로다(ADR-016 · §8.6 — 폴백이 있는 의존성을 레디니스에 넣으면 Redis 장애가 곧
   서비스 차단이다). 첫 연결이 핫패스 밖으로 나간다.
3. **알림의 `for` 는 건드리지 않는다.** 30–40초의 우회는 실제로 보상 통제가 없던 시간이고, 알림을 늦춰 조용하게 만드는 것은 결함을
   고치는 것이 아니라 숨기는 것이다. 1·2 뒤에도 창 시작에 우회가 남으면 그때 `for` 를 논한다(7-4 turbulent 실행이 본다).
4. **order · fulfillment 둘 다.** 같은 커스터마이저 모양을 쓰고, 같은 로그(`geo:fc` 50 ms)가 fulfillment 에서도 나왔다.
   dispatch · tracking 은 명령 타임아웃을 줄이지 않았으므로(Boot 기본 60초) 이 결함이 없다.

## 근거

- **예산이 하나이면 둘 중 하나가 틀린다.** 50 ms 를 연결에 맞춰 늘리면 Redis 가 *멈췄을 때* 요청마다 그만큼을 버리고(§7.2 의 SLO 논거),
  연결을 50 ms 에 두면 콜드 경로의 핸드셰이크가 실패한다. 가르면 둘 다 맞는다.
- **선연결은 게으름을 없애지 않고 위치를 옮긴다.** 기동 때 Redis 가 없으면 선연결은 실패하고 첫 요청이 다시 연다 — 그때의 예산이 결정
  1 의 2초다. 선연결이 레디니스를 막지 않으므로 ADR-016 의 전제는 그대로다.

## 고려한 대안과 기각 이유

| 대안 | 기각 이유 |
|---|---|
| 명령 타임아웃을 연결에 맞게 늘린다(예: 500 ms) | Redis 가 멈춘 동안 요청마다 그만큼을 기다린다 — 50 ms 를 고른 이유(§7.2)가 사라진다 |
| `spring.data.redis.connect-timeout` 만 준다 | 그 값은 `SocketOptions.connectTimeout`(TCP 연결)만 바꾼다. 핸드셰이크 대기는 여전히 `RedisURI.timeout` = 50 ms 다(바이트코드로 확인) |
| `LettuceConnectionFactory.setEagerInitialization(true)` | 기동 경로 안에서 연결한다 — Redis 가 없을 때 기동이 그 예산만큼 늘거나 실패한다. best-effort 가 아니다 |
| 레디니스에 Redis 연결을 넣는다 | ADR-016 · §8.6 — 폴백이 있는 의존성을 레디니스에 넣으면 Redis 장애가 곧 트래픽 차단이다 |
| 알림에 `for: 1m` | 결정 3 — 결함을 숨긴다 |

## 결과

- **장점**: 창 시작의 우회가 콜드 경로에서 사라진다(검증은 turbulent 실행의 `bypassed` 와 알림). 명령 예산 50 ms 는 그대로다.
- **비용**: 연결 객체가 없을 때의 첫 명령은 최대 2초를 기다릴 수 있다 — 선연결이 실패했고(기동 때 Redis 부재) 호스트가 응답 없이
  패킷을 버리는 경우다. 거부(connection refused)나 이름 해석 실패는 즉시 끝난다. 그 한 번 뒤에는 게이트가 10초 동안 건너뛴다
  (근거: 추정 — 블랙홀 장애는 재지 않았다). 연결이 한 번 선 뒤의 끊김은 Lettuce 가 백그라운드에서 다시 잇고, 그동안의 명령은
  `TimeoutOptions` 의 50 ms 에서 끝난다.
- **되돌리는 방법**: `RedisTimeoutConfig` 의 두 커스터마이저를 이전의 `builder.commandTimeout(commandTimeout)` 하나로 되돌리고
  선연결 리스너를 지운다. 속성 `connect-timeout*` 은 남아도 해가 없다.

## 검증

| 표본 | 기대 | 결과 |
|---|---|---|
| `RedisConnectTimeoutIT`(order · fulfillment) — 새 연결의 첫 응답(HELLO)을 200 ms 늦추는 프록시 뒤에서 선연결 | 연결된다(걸린 시간 ≥ 200 ms 가 전제) | ✅ 둘 다 |
| 같은 IT — 연결된 뒤 모든 응답을 200 ms 늦추면 명령 하나 | 200 ms 전에 `QueryTimeoutException` | ✅ 둘 다 (0.12 초 — 컨텍스트 포함) |
| 같은 IT — 선연결 뒤 명령 둘 | 프록시가 받은 연결 수 1 (명령이 새 연결을 열지 않는다) | ✅ 둘 다 |
| 같은 IT — Redis 가 없다(프록시를 닫는다) | 선연결이 `false` 를 돌려주고 던지지 않는다 | ✅ 둘 다 |
| 음성 표본 — order 의 두 커스터마이저를 정정 전 모양(`commandTimeout(50ms)` · `TimeoutOptions.enabled()`)으로 | 첫 IT 만 빨강 | ✅ 첫 IT 하나만 빨강, WARN 이 `RedisCommandTimeoutException: Connection initialization timed out after 50 millisecond(s)` — overload-day 로그와 같은 문장. 복원 뒤 `cmp` 일치 |

첫 IT 는 처음에 프록시가 **모든** 응답을 늦춘 채 선연결을 불러 빨강이었다 — 연결은 됐지만 뒤따르는 `PING` 이 명령 예산에 걸렸다. 결함이 아니라
표본이 틀렸다: 콜드 경로의 모양은 「연결 수립이 느리고 그 뒤 명령은 정상」이다. 그래서 프록시에 「새 연결의 첫 응답만」 손잡이를 두었다.
