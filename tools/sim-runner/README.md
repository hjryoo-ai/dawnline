# tools/sim-runner — 시나리오 CLI

시나리오 YAML 로 (a) 주문을 만들어 order-service 에 넣고 (b) `route.assigned` 를 구독해
기사가 라우트를 도는 것까지 한다 (DESIGN.md §5.6).

**기사는 시나리오에 `driver` 절이 있을 때만 돈다.** 없으면 주문만 넣는다 — 그래야 `smoke` 가
브로커 없이 돈다. 리스너 컨테이너는 꺼진 채로 등록되고, 켜는 것은 `DriverScenario` 다.

## 실행

```bash
make up                     # 또는 make up-lean
make smoke                  # scenarios.yml 의 smoke — 주문 200건
make smoke SCENARIO=tiny    # 10건. 스택이 살아 있는지만 볼 때
make smoke SIM_BASE_URL=http://localhost:9081

# gradle 로 직접
./gradlew :tools:sim-runner:bootRun --args='--dawnline.sim.scenario=smoke'

# 기사까지 — 늦게 출발한 기사를 주입한다 (Phase 5-2)
./gradlew :tools:sim-runner:bootRun --args='--dawnline.sim.scenario=late-injection'
```

시나리오는 `src/main/resources/scenarios.yml` 에 있다. 새 시나리오는 거기에 이름을 하나
더 만들면 되고, 코드를 고치지 않는다.

## 이것은 부하 테스트가 아니다

부하는 k6 가 잰다(`tools/k6/orders.js`). 여기서 `rate-per-second` 를 두는 것은 **흐름**을
만들기 위해서다 — 200건이 한꺼번에 쏟아지면 Phase 2 의 웨이브 편입이나 Phase 3 의 계획이
실제 하루와 전혀 다른 모양을 보게 된다. 출력의 p50/p95/p99 는 참고값이지 SLO 가 아니다.

## 두 가지를 지킨다

**결정론** — 같은 `seed` 면 같은 주문 200건이 나온다(불변규칙 12). 시간·난수는
`config/SimRunnerConfig` 에서만 만들고 나머지는 전부 주입받는다. 이 파일 밖에서
`System.nanoTime()` 이나 `new Random()` 이 보이면 결함이다.

**실패를 삼키지 않는다** — 한 건이 실패해도 계속 보내되, 끝에 Problem Details 의 `code` 별로
몇 건인지 말한다. 그리고 하나라도 접수되지 않으면 0 이 아닌 종료 코드로 끝난다.
`make demo` 가 "성공" 이라고 말한 뒤 DB 가 비어 있는 상황을 만들지 않기 위해서다.

## 기사 시뮬레이터 (Phase 5-2)

**시뮬레이션 시각은 벽시계가 아니다.** 스캔의 `occurredAt` 은 계약의 `plannedDeparture`·
`plannedArrival` 에 seed 에서 뽑은 지연을 더한 값이고 `Instant.now()` 가 아니다. 그래야
*주입한 지연이 곧 tracking 이 계산하는 편차*가 되어 「늦었다」를 값으로 확인할 수 있다.
배속(`speed`)은 호출 사이의 대기에만 닿으므로 같은 seed 는 배속과 무관하게 같은 스캔 열을 낸다.

그래서 적어 둘 것 하나: at-risk 쿨다운 TTL 은 **벽시계 5분**인데 `late-injection` 은 600배속이라
라우트당 at-risk 가 한 번만 보인다. **시뮬레이터의 제약이지 tracking 의 규칙이 아니다**
(ADR-046: 쿨다운이 지키는 것은 알림 수다). 횟수를 보려면 `speed: 1` 로 두고 실제 시간만큼
기다린다.

**개정이 오면 현재 위치에서 다시 계획한다.** `DriverSimulator` 는 「라우트 → 스캔 열」이 아니라
「라우트 + 현재 위치 → 남은 스캔 열」이다. 이미 끝낸 stop 은 새 개정이 뭐라 하든 다시 스캔하지
않고, 그 판단은 `seq` 가 아니라 **주문 id** 로 한다 — 개정이 순서를 바꾸면 같은 `seq` 가 다른
지점을 가리키기 때문이고, tracking 이 배송 단위로 판단하는 것과 같은 축이다.

**404 는 재시도하되 상한이 있다.** tracking 과 이 도구는 같은 토픽을 다른 컨슈머 그룹으로 읽어
이 도구가 먼저 읽는 일이 있다. 다만 조용히 무한 재시도하면 시나리오 결과가 오염되므로
(`scan-retry-seconds`) 를 넘기면 그 라우트를 포기하고 로그·카운터로 말한다.

**`processed_events` 가 없다** — 불변규칙 2 의 예외다. 성립하는 이유는 「도구라서」가 아니라
**하류가 멱등이라서**다: 중복이 만드는 것은 tracking 으로 가는 중복 스캔이고 §8.5 의
「`(routeId, seq, type)` + 상태 머신」이 `STALE` 로 흡수한다. 하류가 멱등이 아닌 도구는 같은
예외를 쓸 수 없다 (DESIGN.md §13 매핑표).

## 알아 둘 결합

`OrderGenerator.POSTAL_PREFIXES` 는 order-service 의 `PostalPrefixGeocoder.ANCHORS` 와 같아야
한다. 그 표는 아직 어떤 계약 파일에도 없다 — 권역 데이터의 주인은 Phase 2 의
fulfillment-service 이기 때문이다. 지금은 **어긋나면 드러나게** 두었다: 어긋난 접두어는 좌표
조회에 실패해 400 이 되고, 실행이 `validation-failed` 건수와 함께 실패로 끝난다.
`tools/k6/lib/orders.js` 도 같은 표를 갖고 있고 같은 이유로 같은 처지다.
