-- =============================================================================
-- dispatch-service 스키마 (DESIGN.md §5.3)
--
-- 공통 테이블(outbox_events, processed_events)은 libs/messaging 이 클래스패스로 주는
-- V000_x 스크립트가 만든다. 여기에는 이 서비스 고유의 것만 둔다.
--
-- 인덱스는 §5.3 DDL 에 명시된 것만 만든다 (CLAUDE.md 불변규칙 11).
-- 참조 데이터(vehicles·drivers·dispatch_rules)의 값은 R__seed_dispatch.sql 이 넣는다.
-- =============================================================================

-- --- 자원 -------------------------------------------------------------------

CREATE TABLE vehicles (
  id                UUID PRIMARY KEY,                  -- UUIDv7 (불변규칙 10)
  camp_id           UUID NOT NULL,                     -- fulfillment 의 camps.id. FK 를 걸지 않는다
                                                       -- (불변규칙 3 — 서비스 간 FK 금지)
  code              VARCHAR(16) NOT NULL UNIQUE,       -- 운영자가 부르는 이름 (V-001)
  type              VARCHAR(16) NOT NULL,              -- BIKE | VAN | TRUCK (§6.3 VEHICLE_PREFERENCE)
  max_weight_g      INTEGER NOT NULL CHECK (max_weight_g > 0),
  max_volume_cm3    INTEGER NOT NULL CHECK (max_volume_cm3 > 0),
  is_cold           BOOLEAN NOT NULL DEFAULT FALSE,
  allows_hazmat     BOOLEAN NOT NULL DEFAULT FALSE,
  fixed_cost_krw    INTEGER NOT NULL CHECK (fixed_cost_krw >= 0),   -- 불변규칙 9: 정수 KRW
  cost_per_km_krw   INTEGER NOT NULL CHECK (cost_per_km_krw >= 0),
  cost_per_min_krw  INTEGER NOT NULL CHECK (cost_per_min_krw >= 0),
  -- 근무창은 벽시계다. 계획 대상 날짜에 붙이는 일은 어댑터가 한다 (VehicleSpec.shift 주석).
  shift_start       TIME NOT NULL,
  shift_end         TIME NOT NULL,
  active            BOOLEAN NOT NULL DEFAULT TRUE
);

COMMENT ON COLUMN vehicles.camp_id IS
  'fulfillment 의 camps.id 를 값으로만 들고 있다. 서비스 간 FK 는 불변규칙 3 이 금지한다.';

CREATE TABLE drivers (
  id          UUID PRIMARY KEY,
  camp_id     UUID NOT NULL,
  vehicle_id  UUID REFERENCES vehicles(id),
  code        VARCHAR(16) NOT NULL UNIQUE,
  name        TEXT NOT NULL,
  status      VARCHAR(16) NOT NULL                     -- AVAILABLE | ON_ROUTE | OFF
);

-- --- 계획 후보 ---------------------------------------------------------------
--
-- fulfillment.planned 를 소비해 채운다. order_id 가 PK 라 같은 주문이 두 번 와도 한 행이다
-- (멱등, 불변규칙 2 의 processed_events 와 함께 두 겹).

CREATE TABLE dispatch_candidates (
  order_id        UUID PRIMARY KEY,
  wave_id         UUID NOT NULL,
  camp_id         UUID NOT NULL,
  zone_id         UUID,
  lat             NUMERIC(9,6) NOT NULL,               -- 불변규칙 9
  lng             NUMERIC(9,6) NOT NULL,
  geohash7        CHAR(7) NOT NULL,                    -- stop 통합 키 (§6.5 1단계)
  weight_g        INTEGER NOT NULL CHECK (weight_g >= 0),
  volume_cm3      INTEGER NOT NULL CHECK (volume_cm3 >= 0),
  requires_cold   BOOLEAN NOT NULL DEFAULT FALSE,
  hazmat          BOOLEAN NOT NULL DEFAULT FALSE,
  promised_start  TIMESTAMPTZ NOT NULL,
  promised_end    TIMESTAMPTZ NOT NULL,
  service_seconds INTEGER NOT NULL CHECK (service_seconds >= 0),
  priority        SMALLINT NOT NULL DEFAULT 0 CHECK (priority >= 0),
  -- PENDING | PLANNED | CANCELLED | UNASSIGNED. 취소는 행을 지우지 않는다 (ADR-026) —
  -- "주문 X 는 왜 라우트에 없나" 에 답할 수 있어야 한다 (§6.3 설명 가능성).
  status          VARCHAR(16) NOT NULL,
  version         BIGINT NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ NOT NULL,
  updated_at      TIMESTAMPTZ NOT NULL
);

COMMENT ON COLUMN dispatch_candidates.status IS
  '취소된 후보도 행으로 남는다 (ADR-026). 지우면 "왜 라우트에 없나" 에 답할 수 없다.';

-- §5.3 DDL 명시. 계획이 "이 웨이브의 PENDING 후보" 를 집는 질의가 유일한 뜨거운 경로다.
CREATE INDEX ix_cand_wave ON dispatch_candidates (wave_id, status);

-- --- 계획과 라우트 -----------------------------------------------------------

CREATE TABLE route_plans (
  id                UUID PRIMARY KEY,
  -- UNIQUE 가 wave.closed 중복 도착의 멱등을 만든다 (§5.3): 두 번째는 기존 plan 을 발견하고 끝난다.
  wave_id           UUID NOT NULL UNIQUE,
  camp_id           UUID NOT NULL,
  -- REQUESTED | PLANNING | PLANNED | PUBLISHED | FAILED (§5.3 Plan 상태 머신)
  status            VARCHAR(16) NOT NULL,
  strategy          VARCHAR(32),                       -- route.assigned.strategy 와 같은 값
  mode              VARCHAR(8),                        -- FULL | FAST (§6.7 열화 모드)
  seed              BIGINT,                            -- 같으면 같은 결과 (불변규칙 12)
  rule_version      INTEGER,                           -- 어떤 룰로 계획했는가 (§6.3)
  started_at        TIMESTAMPTZ,
  finished_at       TIMESTAMPTZ,
  total_cost_krw    BIGINT,
  assigned_count    INTEGER,
  unassigned_count  INTEGER,
  plan_duration_ms  INTEGER,
  failure_reason    VARCHAR(32),                       -- plan.failed.reason
  version           BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE routes (
  id          UUID PRIMARY KEY,
  plan_id     UUID NOT NULL REFERENCES route_plans(id),
  vehicle_id  UUID NOT NULL REFERENCES vehicles(id),
  driver_id   UUID REFERENCES drivers(id),
  seq_no      SMALLINT NOT NULL,
  status      VARCHAR(16) NOT NULL,                    -- PLANNED | DISPATCHED | COMPLETED
  -- 최초 확정이 1, 재계획마다 증가 (§6.8 4단계, route.assigned.revision).
  revision    INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1),
  stop_count  INTEGER NOT NULL CHECK (stop_count >= 0),
  distance_m  INTEGER NOT NULL CHECK (distance_m >= 0),
  duration_s  INTEGER NOT NULL CHECK (duration_s >= 0),
  cost_krw    BIGINT NOT NULL,
  version     BIGINT NOT NULL DEFAULT 0
);

-- FK 대상 컬럼에는 전체 인덱스를 둔다 (§7.1 — 부분 인덱스는 참조 무결성 검사에 쓰이지 않는다).
-- Phase 2 에서 이것이 없어 웨이브 삭제가 7초 걸린 적이 있다 (ADR-022 후속 정정).
CREATE INDEX ix_routes_plan ON routes (plan_id);

CREATE TABLE route_stops (
  id                UUID PRIMARY KEY,
  route_id          UUID NOT NULL REFERENCES routes(id),
  seq               SMALLINT NOT NULL CHECK (seq >= 1),
  lat               NUMERIC(9,6) NOT NULL,
  lng               NUMERIC(9,6) NOT NULL,
  planned_arrival   TIMESTAMPTZ NOT NULL,
  planned_departure TIMESTAMPTZ NOT NULL,
  service_s         INTEGER NOT NULL CHECK (service_s >= 0),
  -- PLANNED | CANCELLED | ARRIVED | COMPLETED. CANCELLED 는 취소된 주문의 stop 이고
  -- 페이로드에서도 지우지 않는다 (ADR-026 — 부재는 값이 아니다).
  status            VARCHAR(16) NOT NULL,
  UNIQUE (route_id, seq)
);

CREATE TABLE route_stop_orders (
  stop_id   UUID NOT NULL REFERENCES route_stops(id),
  order_id  UUID NOT NULL,
  PRIMARY KEY (stop_id, order_id)
);

-- --- 룰과 설명 ---------------------------------------------------------------

CREATE TABLE dispatch_rules (
  id            UUID PRIMARY KEY,
  -- NULL 이면 전역, 값이 있으면 그 캠프의 오버라이드다 (§6.3).
  camp_id       UUID,
  name          VARCHAR(64) NOT NULL,
  type          VARCHAR(48) NOT NULL,
  severity      VARCHAR(8) NOT NULL CHECK (severity IN ('HARD','SOFT')),
  params        JSONB NOT NULL,
  priority      SMALLINT NOT NULL,
  enabled       BOOLEAN NOT NULL DEFAULT TRUE,
  -- 룰 변경은 이 값을 올린다. 진행 중 계획은 시작 시점 스냅샷을 쓴다 (§6.3).
  rule_version  INTEGER NOT NULL DEFAULT 1,
  updated_at    TIMESTAMPTZ NOT NULL,
  -- 같은 범위에 같은 이름이 둘이면 Explanation.ruleName 으로 어느 룰인지 알 수 없다.
  UNIQUE (camp_id, name)
);

CREATE TABLE plan_explanations (
  id         UUID PRIMARY KEY,
  plan_id    UUID NOT NULL REFERENCES route_plans(id),
  order_id   UUID,
  vehicle_id UUID,
  rule_name  VARCHAR(64),
  outcome    VARCHAR(16) NOT NULL,                     -- ASSIGNED | UNASSIGNED
  detail     JSONB NOT NULL
);

-- §5.3 DDL 명시. 운영자의 "이 주문은 왜 이렇게 됐나" 질의가 이 인덱스를 탄다.
CREATE INDEX ix_expl_plan_order ON plan_explanations (plan_id, order_id);
