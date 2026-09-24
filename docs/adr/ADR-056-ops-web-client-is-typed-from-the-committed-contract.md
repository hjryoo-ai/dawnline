# ADR-056 — ops-web 의 클라이언트는 커밋된 계약에서 타입을 받는다 — 채택 기준을 먼저 적는다

| 항목 | 내용 |
|---|---|
| 상태 | Proposed — 후보 1 은 **기준 5 거짓으로 기각**(아래). 후보 2 는 사용자가 골랐고(2026-09-24), 같은 기준으로 시도 전이다 |
| 결정일 | 2026-09-24 |
| 관련 문서 | `docs/DESIGN.md` §5.5 「조회」 · §11 · §13 · `docs/IMPLEMENTATION_PLAN.md` Phase 6 작업 3 (묶음 C2) · `CLAUDE.md` 「서로를 비추는 목록에는 대조 검사를 둔다」 |
| 관련 ADR | [ADR-052](ADR-052-delegation-client-is-generated-from-the-committed-contract.md) (같은 원칙의 Java 쪽 — ops-api 가 코어의 문서에서 위임 클라이언트를 만든다) |

---

## 맥락

묶음 C1 이 `contracts/openapi/ops-api.yaml` 을 만들었다(§11). 그 문서의 첫 소비자가 ops-web 이다 —
ADR-052 에서 ops-api 가 코어 문서의 첫 소비자였던 것과 같은 자리다. 사용자 결정(2026-09-24):
**프론트의 계약도 그 문서다.** TS 클라이언트는 그 문서에서 생성하고(openapi-typescript 계열), 생성물은
빌드 산출물이다.

C1 이 이 결정을 위해 미리 한 일이 하나 있다: springdoc 은 JSpecify 를 모르므로 응답 스키마의 칸이 전부
선택으로 그려졌고, 그 문서로 만든 TS 타입은 **코드보다 약하게 말했다.** `NullabilityRequiredConverter` 가
`@Nullable` 이 없는 레코드 컴포넌트를 `required` 로 옮긴다. 이 ADR 의 기준 4 는 그 일이 TS 까지 도착하는지를
본다.

## 결정

### 1. 생성을 시도한다 — 채택 기준은 시도 **전에** 여기 적는다

기준을 시도 뒤에 적으면 결과를 보고 기준을 고르게 된다. 그래서 이 절은 첫 생성보다 먼저 커밋한다.

**후보는 한 쌍이다**: `openapi-typescript`(문서 → `paths`·`components` 타입, 개발 의존)와
`openapi-fetch`(그 타입을 받는 `fetch` 래퍼, 런타임 의존). 생성물은 **타입뿐**이고 런타임 코드가 없다 —
HTTP 는 브라우저의 `fetch` 가 한다. 코드 생성형(`openapi-generator` 의 `typescript-fetch` 등)은 자기
런타임 클래스를 생성물 안에 가져온다 — ADR-052 가 `java` 생성기를 후보로 삼지 않은 것과 같은 이유로
후보로 삼지 않는다(**근거: 추정** — 생성해 보지 않았다).

**미리 보이는 위험 하나**: `openapi-typescript` 7.13 의 peer 는 `typescript ^5.x` 이고, 지금 TypeScript
최신은 7.0 이다(6.0 도 있다). 생성기는 TypeScript 의 컴파일러 API 로 출력을 찍는다 — 7.x 에서 그 API 가
그대로인지는 모른다(**근거: 추정**). 아래 기준 2 가 이것을 판정한다.

**채택한다** — 아래가 전부 참일 때:

1. **표준 출력.** 생성 뒤 파일을 고치는 후처리가 없고, 생성기의 `transform`·`postTransform` 훅(스키마 노드를
   다른 타입으로 바꿔 끼우는 자리)을 쓰지 않는다 — 그것은 ADR-052 의 `typeMappings` 와 같은 **심**이다.
   CLI 옵션은 생성기가 문서화한 것만.
2. **설치가 peer 범위 안에서 성립한다.** `npm ci` 가 `--legacy-peer-deps`·`--force`·`overrides` 없이
   경고 없이 끝난다. TypeScript 는 그래서 **생성기의 peer 가 정한다** — 앱의 타입 검사도 같은 판을 쓴다.
   둘을 가르면(생성기는 5, 앱은 7) 「생성물이 앱에서 컴파일된다」가 두 판 사이의 문장이 되고, 그것은
   검사가 아니라 우연이다.
3. **생성물이 그대로 컴파일된다.** 앱의 `tsc --noEmit`(`strict` · `noUncheckedIndexedAccess` ·
   `exactOptionalPropertyTypes`)에서 오류 0. 생성물 파일을 검사에서 빼지 않는다.
4. **계약의 `required` 가 타입에 도착한다.** `required` 에 있는 칸은 선택(`?`)이 아니고, 없는 칸은
   선택이다 — 응답 셋(`CampKpi` · `WaveRoutes` · `RouteStop`, C1 의 `OpenApiContractIT` 가 문서 쪽에서 보는
   그 셋)에서 **타입 수준 픽스처**로 본다(`@ts-expect-error` 가 붙은 줄이 오류가 아니면 `tsc` 가 실패한다).
   **음성 표본**: 문서의 사본에서 한 칸의 `required` 를 빼고 생성하면 그 픽스처가 빨갛다.
5. **계약에 없는 호출이 컴파일에서 막힌다.** 없는 경로, 그 경로에 없는 메서드, 요청 본문의 **계약 밖 칸** —
   셋 다 `tsc` 오류다. 마지막이 아래 결정 2 의 문장을 기계가 지키게 하는 자리다: 재배정의 본문은
   `targetRouteId` 하나이고 `{ targetRouteId, reason }` 은 **객체 리터럴로 넘길 때** 오류여야 한다.
   **음성 표본**: 픽스처의 `@ts-expect-error` 를 지우면 `tsc` 가 그 줄을 오류로 말한다.
6. **오류 본문이 타입으로 읽힌다.** 401·403(C1 이 모든 연산에 붙였다)과 각 연산의 4xx·5xx 가
   `ProblemDetail` 로 타입이 잡히고 `code` 칸이 있다 — 화면은 401 과 403 을 상태 코드가 아니라 `code`
   (`unauthenticated`·`forbidden`)로 가른다.
7. **런타임 의존은 `openapi-fetch` 와 그 트리뿐이다.** 번들에 들어가는 새 패키지가 그 둘(`openapi-fetch`,
   `openapi-typescript-helpers`)을 넘지 않는다.

**기각한다** — 하나라도 거짓이면. 그때는 손으로 쓴 TS 타입 + **YAML 대조 테스트**(Vitest)다 — 스키마의
속성 이름과 `required` 를 YAML 에서 **전부 읽고** 손으로 쓴 타입의 것과 맞춘다(열거하지 않는다, §13).
타입을 손으로 쓰면 그 대조 테스트가 계약의 유일한 가드가 되므로, 기각은 비용이 아니라 **검사의 자리가
옮겨 가는 것**이다.

**채택하면 조건 셋** (ADR-052 와 같다):

- 입력은 살아 있는 ops-api 가 아니라 **커밋된 `contracts/openapi/ops-api.yaml`** 이다. 문서와 코드의 일치는
  ops-api 의 `OpenApiContractIT` 가 이미 본다 — ops-web 은 그 문서만 믿는다.
- 생성은 `typecheck`·`test`·`build` 앞에서 매번 돈다(`npm` 의 `pre*` 스크립트). 문서만 바뀐 PR 에서도
  ops-web 의 타입 검사가 다시 돈다 — CI 의 `ops-web` job 은 경로 필터 없이 돈다.
- 생성물은 커밋하지 않는다(`.gitignore`). 버전은 `package.json` 에 **정확히** 고정하고 `package-lock.json`
  을 커밋한다.

### 2. 화면의 입력은 계약에 있는 칸만

화면의 폼은 요청 본문의 칸을 **넘지 않는다.** 재배정 확인 창에는 「이유」 칸이 없다 — 재배정의 계약
본문은 `targetRouteId` 하나이고, 화면에서만 받는 이유는 감사 행에도 코어에도 가지 않는 **버려지는 입력**
이다. 운영자는 적었다고 믿고, 기록은 그것을 모른다. 조기 마감의 이유는 계약의 `reason` 이라 필수로 받는다.
화면에 칸이 필요해지면 **계약이 먼저 바뀐다** — ops-api 의 요청 레코드, 그다음 생성물, 그다음 화면. 결정 1
의 기준 5 가 이 순서를 컴파일 오류로 강제한다.

## 시도 결과 — 기준 5 거짓 (2026-09-24)

`openapi-typescript` **7.13.0** · `openapi-fetch` **0.17.0** · `typescript` **5.9.3**(생성기의 peer `^5.x` 안의
최신). 판정은 `.nvmrc` 의 Node 24 에서(`node:24` 컨테이너, v24.21.0) — 로컬의 Node 25 는 `jsdom` 의 engines
범위 밖이라 그 경고로 기준 2 를 판정하지 않았다.

| 기준 | 결과 | 근거 |
|---|---|---|
| 1. 표준 출력 | ✅ | CLI 인자는 입력·출력 경로뿐. `transform`·`postTransform` 없음, 후처리 없음 |
| 2. peer 범위 안의 설치 | ✅ | Node 24 에서 `npm ci` — 132개, peer·engines 경고 0. `--legacy-peer-deps`·`overrides` 없음 |
| 3. 그대로 컴파일 | ✅ | 생성물을 `.d.ts` 가 아니라 `.ts` 로 받는다 — `skipLibCheck` 가 `.d.ts` 를 건너뛰므로. `strict` · `noUncheckedIndexedAccess` · `exactOptionalPropertyTypes` 에서 생성물의 오류 0 |
| 4. `required` 도착 | ✅ | `CampKpi` · `WaveRoutes` · `RouteStop` 픽스처 통과, `@Nullable` 칸은 `null` 을 받는다. **음성 표본**: 문서 사본에서 `CampKpi.campId` 의 `required` 를 빼면 `Unused '@ts-expect-error'` (17행) |
| 5. 계약 밖 호출은 컴파일 오류 | ❌ | 없는 경로 ✅ · 없는 메서드 ✅ · 필수 본문 칸 누락 ✅ — **본문의 계약 밖 칸은 오류가 아니다.** `{ targetRouteId, reason }` 이 통과한다(스프레드 없는 평범한 리터럴에서도, 조기 마감 본문의 `extra` 에서도) |
| 6. 오류 본문의 `code` | ✅ | `GET /camps` 의 `error` 가 `ProblemDetail` 에 대입되고 `code` 를 읽는다 |
| 7. 런타임 의존 둘 | ✅ | `npm ls --omit=dev`: `openapi-fetch` → `openapi-typescript-helpers` 뿐(React·Leaflet 제외) |

**기준 5 가 거짓인 이유 — 근거: 관측(재현됨).** `openapi-fetch` 의 메서드 서명이
`<Path, Init extends MaybeOptionalInit<Paths[Path], Method>>(url: Path, ...init: InitParam<Init>)` 다
(`dist/index.d.ts` 의 `ClientMethod`). 본문을 담은 `Init` 이 **제네릭 파라미터**라서 인자에서 추론되고, 추론된
타입은 제약에 **대입 가능한지**만 검사받는다 — 초과 속성 검사는 신선한 객체 리터럴이 **정해진 타입**에 대입될
때만 돈다. 문서화된 옵션 중에 이것을 바꾸는 것은 없다. 그래서 결정 2 의 문장 — 화면의 입력은 계약에 있는
칸만 — 을 이 쌍은 컴파일로 지키지 못한다: 재배정 창에 이유 칸을 더한 코드가 초록으로 지나간다.

**생성물 쪽(`openapi-typescript`)은 기준 1–4·6 을 전부 통과했다.** 거짓인 것은 호출 층의 서명 하나다.

**시도가 드러낸 C1 문서의 거짓 하나 — C1 PR 에서 고쳤다.** 본문은 `@Nullable` 칸을 빼지 않고 `null` 을
싣는데(`"planId":null` — 근거: 관측) 문서는 그 칸을 「선택」으로만 적었다. 그 문서로 만든 타입은
`string | undefined` 여서 실제로 오는 `null` 을 몰랐다. `NullabilityRequiredConverter` 가 `@Nullable` 칸의
타입에 `null` 을 더한다(§11).

## 후보 2 — 생성된 타입 + 손으로 쓴 얇은 호출 층 (2026-09-24, 사용자 결정)

후보 1 의 시도는 **생성물**(`openapi-typescript`)이 기준 1–4·6 을 전부 통과했고 거짓인 것은 **호출 층의 서명**
하나라는 것을 보였다. 사용자는 결정 1 에 적어 둔 기각 경로(손으로 쓴 타입 + YAML 대조) 대신 이 후보를 골랐다 —
타입의 출처는 그대로 커밋된 문서이고, 바뀌는 것은 호출 층뿐이다. **결과를 본 뒤 고른 후보이므로 기준은 그대로
둔다** — 결정 1 의 일곱을 한 글자도 고치지 않고 이 후보에 다시 건다. 이 절은 시도보다 먼저 커밋한다.

**모양**: `openapi-fetch` 를 빼고, 화면이 부르는 연산마다 함수 하나(조회 여섯 · 재배정 · 조기 마감 — 여덟).
- 본문 파라미터는 **제네릭이 아니라 정해진 타입**이다 — `reassign(routeId, orderId, body: Schemas['ReassignBody'])`.
  신선한 객체 리터럴이 정해진 타입에 대입될 때 초과 속성 검사가 돈다(기준 5 가 거짓이었던 바로 그 이유의 반대).
- 응답 타입은 생성된 `operations['<operationId>']['responses'][200]` 에서 뽑는다 — 손으로 쓰지 않는다.
- 경로는 생성된 `paths` 의 키로만 받는 내부 함수 하나를 지난다 — 없는 경로·그 경로에 없는 메서드가 컴파일 오류다.
- HTTP 는 브라우저의 `fetch` 다.

**기준을 이 후보에 읽는 법** — 문장은 그대로이고 적용만 적는다:
- 기준 1·3·4·6 은 생성물에 대한 것이라 후보 1 의 판정이 그대로 다시 돈다(같은 생성물, 같은 픽스처).
- 기준 2 는 `openapi-fetch` 가 빠진 설치로 다시 판정한다.
- 기준 5 는 호출 층의 함수들로 다시 판정한다 — 픽스처는 후보 1 의 것을 호출 층으로 옮긴 것이다.
- 기준 7 의 「`openapi-fetch` 와 그 트리뿐」은 상한이다 — 이 후보의 런타임 의존은 **0** 이어야 참이다.

**대가**: 연산이 늘면 함수가 는다. 화면이 부르는 것만 쓰고, 문서가 바뀌면 그 함수의 타입이 생성물에서 오므로
컴파일이 알려 준다 — 손으로 쓴 것은 경로 문자열과 함수 이름뿐이고, 경로 문자열도 `paths` 의 키로 검사된다.

