# deploy/compose — Dawnline 로컬 전체 스택

`docs/DESIGN.md` §3.1(구성) · §4.1(토픽) · §7.1(DB-per-service) · §7.3(Kafka) · §9(관측성) · §14(로컬 실행)의 구현체다.
모든 조작은 저장소 루트의 `Makefile` 을 통한다. `make help` 로 타깃 목록을 볼 수 있다.

---

## ⚠️ 먼저: Docker 리소스

> **권장: CPU 6코어 이상, 메모리 12GB 이상**
> (Docker Desktop → Settings → Resources)

전체 스택은 컨테이너 **12개**(인프라 4 + 관측성 4 + 서비스 5, 그중 `kafka-init` 은 1회성)를 띄운다.
컨테이너마다 메모리 상한을 걸어 두었지만 합계가 대략 **5.5GB** 라, Docker 에 6GB 만 할당돼 있으면
서비스가 OOM 으로 재시작하거나 Kafka 힙이 부족해진다.

메모리를 늘릴 수 없다면 다음 순서로 줄인다.

| 상황 | 명령 | 뜨는 컨테이너 |
|---|---|---|
| 개발·통합 테스트만 | `make up-infra` | postgres, kafka, kafka-init, redis |
| 서비스까지 (관측성 제외) | `make up-lean` | 위 + 서비스 5개 |
| 전체 (데모용) | `make up` | 위 + prometheus, grafana, tempo, otel-collector |

컨테이너별 메모리 상한은 `docker-compose.yml` 의 `deploy.resources.limits` 에,
JVM 힙은 `.env` 의 `SERVICE_JAVA_TOOL_OPTIONS` / `KAFKA_HEAP_OPTS` 에 있다.

---

## 빠른 시작

```bash
make env                 # deploy/compose/.env 생성 (있으면 건드리지 않는다)
make images              # ./gradlew bootBuildImage — 서비스 이미지 5개 (첫 실행은 오래 걸린다)
make up                  # 전체 스택 기동 + 레디니스 대기 + URL 출력
make ps
make down                # 컨테이너만 정지·삭제 (데이터 볼륨은 남는다)
```

인프라만 필요하면 `make images` 없이 `make up-infra` 로 바로 시작할 수 있다.

---

## 구성 요소

### 인프라 (프로파일 없음 — 항상 기동)

| 컨테이너 | 이미지 | 호스트 포트 | 비고 |
|---|---|---|---|
| `postgres` | `postgres:18.2` | 5432 | DB 5개 + 서비스별 계정 (§7.1) |
| `kafka` | `apache/kafka:4.3.1` | 29092 | KRaft 단일 노드, ZooKeeper 없음 |
| `kafka-init` | `apache/kafka:4.3.1` | — | 1회성. §4.1 토픽 + `.dlq` 생성 |
| `redis` | `redis:8.8.2` | 6379 | appendonly, maxmemory 128MB |

### 관측성 (`--profile obs`)

| 컨테이너 | 이미지 | 호스트 포트 |
|---|---|---|
| `prometheus` | `prom/prometheus:v3.14.0` | 9090 |
| `grafana` | `grafana/grafana:13.1.0` | 3000 |
| `tempo` | `grafana/tempo:2.9.5` | 3200 |
| `otel-collector` | `otel/opentelemetry-collector-contrib:0.159.0` | 4317(gRPC), 4318(HTTP) |

### 코어 서비스 (`--profile app`)

컨테이너 안에서는 **전부 8080** 을 쓰고, 호스트 포트만 다르다.

| 서비스 | 호스트 포트 | DB | 계정 |
|---|---|---|---|
| `ops-api` | 8080 | `dawnline_ops` | `dawnline_ops` |
| `order-service` | 8081 | `dawnline_order` | `dawnline_order` |
| `fulfillment-service` | 8082 | `dawnline_fulfillment` | `dawnline_fulfillment` |
| `dispatch-service` | 8083 | `dawnline_dispatch` | `dawnline_dispatch` |
| `tracking-service` | 8084 | `dawnline_tracking` | `dawnline_tracking` |

이미지는 Buildpacks 산출물(`dawnline/<서비스>:${DAWNLINE_VERSION}`)이다. `make images` 로 만든다.

---

## 버전은 어디서 고정하나

- **인프라 이미지 태그·포트·비밀번호** → `deploy/compose/.env` (원본 `.env.example`)
- **라이브러리 버전** → `gradle/libs.versions.toml`

두 곳 말고 다른 데에 버전을 적지 않는다 (CLAUDE.md).
`.env` 는 커밋하지 않는다. `.env.example` 만 커밋한다.

> `.env` 의 값 중 공백이 들어가는 것(`KAFKA_HEAP_OPTS`, `SERVICE_JAVA_TOOL_OPTIONS`)은
> **반드시 따옴표로 감싼다**. Makefile 이 `set -a; . .env` 로도 읽기 때문이다.

---

## PostgreSQL — DB-per-service (§7.1)

`initdb/01-roles-and-databases.sql`, `initdb/02-schema-privileges.sql` 이 첫 기동 시 실행돼
DB 5개와 동명의 전용 계정을 만들고, 서비스 DB 5개 **그리고 부트스트랩 DB
(`POSTGRES_DB` 가 정한 관리 DB — 기본 `dawnline_admin` — 그리고 `postgres`·`template1`)** 의
`CONNECT` 를 `PUBLIC` 에서 회수한다. 관리 DB 는 이름을 받아쓰지 않고 `current_database()` 로
집어내므로 `.env` 에서 `POSTGRES_DB` 를 바꿔도 초기화가 깨지지 않는다.
그래서 **다른 서비스 계정으로는 접속 자체가 거부된다**(CLAUDE.md 불변 규칙 3의 물리적 강제).
슈퍼유저는 ACL 을 우회하므로 `make psql` 은 그대로 동작한다.

```
$ psql -U dawnline_dispatch -d dawnline_order
FATAL:  permission denied for database "dawnline_order"
DETAIL:  User does not have CONNECT privilege.
```

주의할 점 두 가지.

1. **initdb 스크립트는 볼륨이 비어 있는 첫 기동에만 실행된다.**
   스크립트를 고쳤다면 `make clean-volumes` 로 볼륨을 지워야 반영된다.
2. **PostgreSQL 18 이미지는 볼륨을 `/var/lib/postgresql` 에 건다.**
   17 이하처럼 `/var/lib/postgresql/data` 에 걸면 기동이 실패한다
   (`docker-library/postgres#1259`). `docker-compose.yml` 에 이미 반영돼 있다.

비밀번호는 `.env` 의 `DAWNLINE_*_DB_PASSWORD` 를 컨테이너 환경 변수로 넣고,
SQL 이 `\getenv`(PG 14+)로 읽는다. 변수가 없으면 `ON_ERROR_STOP` 으로 기동이 중단된다(조용한 실패 없음).

접속: `make psql` (슈퍼유저 세션)

---

## Kafka — KRaft 단일 노드 (§7.3)

리스너를 둘로 나눠 둔다.

| 용도 | 주소 | 리스너 |
|---|---|---|
| 컨테이너 → 컨테이너 (서비스) | `kafka:9092` | `PLAINTEXT` |
| 호스트 도구 (kcat, IDE, k6) | `localhost:29092` | `EXTERNAL` |

`kafka-init` 이 §4.1 의 토픽 9개와 각각의 `.dlq` 9개, 총 **18개**를 만든다.
파티션 12 / replication 1 / 일반 토픽 `retention.ms` 7일 / DLQ 30일.
`--if-not-exists` 라서 몇 번을 돌려도 안전하다(멱등).

```bash
make topics    # 토픽 목록
```

`KAFKA_CLUSTER_ID` 는 base64 UUID 여야 한다. 새로 만들려면:

```bash
docker run --rm --entrypoint /opt/kafka/bin/kafka-storage.sh apache/kafka:4.3.1 random-uuid
```

---

## 관측성 (§9)

```
서비스 (Spring Boot 4.1, OTLP/HTTP)
   └─▶ otel-collector :4318 ──OTLP/gRPC──▶ tempo :4317 ──▶ Grafana
서비스 /actuator/prometheus ◀── prometheus (10초 간격) ──▶ Grafana
```

- Grafana 는 기동 시 `grafana/provisioning/` 을 읽어 **Prometheus·Tempo 데이터소스를 자동 등록**한다.
  (익명 Viewer 접근 허용. 관리자는 `.env` 의 `GRAFANA_ADMIN_*`)
- **대시보드 JSON 4종은 Phase 7 산출물**이다. 지금은 프로바이더만 있고
  `grafana/dashboards/` 는 비어 있다. JSON 을 넣으면 30초 안에 자동으로 잡힌다.
- **Prometheus 알림 규칙(§9.4)도 Phase 7** 이라 `rule_files` 는 비어 있다.
- 서비스가 안 떠 있으면 Prometheus 타깃 5개가 `DOWN` 으로 보이는 게 정상이다.
- Tempo `metrics_generator`(서비스 그래프)는 Prometheus remote-write 가 필요해서 꺼 두었다. Phase 7.

Spring Boot 4.1 은 `management.opentelemetry.map-environment-variables=true` 가 기본이라
compose 가 넣어 주는 표준 `OTEL_*` 환경 변수(`OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME` …)를
그대로 인식한다. 서비스 쪽에 별도 Spring 프로퍼티를 적을 필요가 없다.

---

## 레디니스 확인

`make up` 은 마지막에 `make wait` 를 돌려 5개 서비스의
`/actuator/health/readiness` 가 200 이 될 때까지(최대 120초) 폴링한다.

```bash
make wait
```

> **왜 컨테이너 `healthcheck` 가 아니라 호스트 폴링인가**
> 서비스 이미지는 Buildpacks 로 만들고, Spring Boot 4.1 의 기본 빌더는
> `paketobuildpacks/builder-noble-java-tiny` 다. 이 런 이미지에는 **셸도 curl 도 wget 도 없어서**
> `healthcheck.test` 로 넣을 수 있는 실행 파일이 없다.
> 그래서 인프라 컨테이너(postgres·kafka·redis)에만 컨테이너 healthcheck 를 걸고,
> 서비스 레디니스는 호스트에서 확인한다. `depends_on` 도 인프라에만 건다.
> (빌더를 `-base` 계열로 바꾸면 컨테이너 healthcheck 를 넣을 수 있다 — 미결 사항)

---

## 데이터 삭제

`make down` 은 **볼륨을 지우지 않는다**. 데이터까지 지우려면:

```bash
make clean-volumes     # 'delete' 를 직접 입력해야 실행된다
```

---

## 문제 해결

| 증상 | 원인 / 조치 |
|---|---|
| `make up` 이 "서비스 이미지가 없다" 로 멈춘다 | `make images` 먼저 (`./gradlew bootBuildImage`) |
| postgres 가 재시작을 반복 | 예전 볼륨이 `/var/lib/postgresql/data` 형식. `make clean-volumes` |
| initdb 수정이 반영되지 않는다 | 첫 기동에만 실행된다. `make clean-volumes` 후 재기동 |
| 서비스가 OOM 으로 죽는다 | Docker 메모리를 늘리거나 `make up-lean` 사용 |
| Kafka healthcheck 가 오래 걸린다 | 첫 기동은 스토리지 포맷 때문에 30~60초 걸린다. 정상 |
| 포트 충돌 | `.env` 의 `*_PORT` 를 바꾼다 |

설정을 고친 뒤에는 항상 문법을 먼저 확인한다.

```bash
make config     # docker compose config --quiet
```
