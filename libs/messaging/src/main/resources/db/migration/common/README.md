# db/migration/common — 모든 서비스가 공유하는 Flyway 스크립트

`libs/messaging` 이 제공하는 `outbox_events` · `processed_events` 두 테이블의 마이그레이션이다
(DESIGN.md §5.1 DDL, §4.4).

## 서비스가 해야 할 설정

이 디렉터리는 Flyway 의 기본 위치(`classpath:db/migration`)가 **아니다**. 서비스는 자기 위치와 함께
명시적으로 추가한다.

```yaml
spring:
  flyway:
    locations: classpath:db/migration/common,classpath:db/migration
```

기본 위치에 그냥 섞어 넣지 않은 이유:

1. **어디서 온 스크립트인지 보인다.** 서비스 저장소를 열었을 때 `db/migration` 에 없는 테이블이
   갑자기 존재하면 추적이 어렵다. `locations` 한 줄이 출처를 말해 준다.
2. **버전 충돌을 막는다.** 기본 위치에 합쳐 두면 서비스가 `V1__...` 을 만들 때마다 라이브러리 쪽
   번호와 겹칠 위험을 신경 써야 한다.
3. **선택할 수 있다.** outbox 를 쓰지 않는 모듈(예: 읽기 전용 프로젝션)은 이 위치를 빼면 된다.

## 버전 번호 규칙

| 접두어 | 소유자 | 예 |
|---|---|---|
| `V000_x__` | `libs/messaging` (이 디렉터리) | `V000_1__outbox_events.sql` |
| `V1__`, `V2__`, … | 각 서비스 | `V1__orders.sql` |

Flyway 는 버전을 `.` 로 나눠 부분별 숫자로 비교하므로 `000.1 < 000.2 < 1` 이다.
공통 스크립트가 항상 먼저 적용되고, 서비스는 1번부터 자유롭게 쓸 수 있다.

**서비스는 `V000_` 접두어를 쓰지 않는다.** 이 범위는 플랫폼 라이브러리 몫이다.

## 새 공통 스크립트를 추가할 때

1. 다음 번호(`V000_3__…`)를 쓴다. 이미 배포된 스크립트는 절대 수정하지 않는다(체크섬 불일치).
2. `docs/DESIGN.md` §5.1 의 DDL 을 먼저 고친다. 설계서가 진실이다.
3. 대응하는 JPA 엔티티의 컬럼과 정확히 맞춘다. `ddl-auto=validate` 가 기동 시 검증한다.
