# tools/sim-runner — 시나리오 CLI

시나리오 YAML 로 주문을 만들어 order-service 에 넣는다 (DESIGN.md §5.6).
**Phase 1 에서는 주문 생성기만** 있다. 기사 시뮬레이터는 라우트가 생기는 Phase 5 의 일이다.

## 실행

```bash
make up                     # 또는 make up-lean
make smoke                  # scenarios.yml 의 smoke — 주문 200건
make smoke SCENARIO=tiny    # 10건. 스택이 살아 있는지만 볼 때
make smoke SIM_BASE_URL=http://localhost:9081

# gradle 로 직접
./gradlew :tools:sim-runner:bootRun --args='--dawnline.sim.scenario=smoke'
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

## 알아 둘 결합

`OrderGenerator.POSTAL_PREFIXES` 는 order-service 의 `PostalPrefixGeocoder.ANCHORS` 와 같아야
한다. 그 표는 아직 어떤 계약 파일에도 없다 — 권역 데이터의 주인은 Phase 2 의
fulfillment-service 이기 때문이다. 지금은 **어긋나면 드러나게** 두었다: 어긋난 접두어는 좌표
조회에 실패해 400 이 되고, 실행이 `validation-failed` 건수와 함께 실패로 끝난다.
`tools/k6/lib/orders.js` 도 같은 표를 갖고 있고 같은 이유로 같은 처지다.
