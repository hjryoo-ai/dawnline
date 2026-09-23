-- =============================================================================
-- ops-api 스키마 — 읽기 모델 네 표 + 감사 기록 (DESIGN.md §5.5)
--
-- 공통 테이블(outbox_events, processed_events)은 libs/messaging 이 클래스패스로 주는
-- V000_x 스크립트가 만든다. 여기에는 이 서비스 고유의 것만 둔다.
--
-- 이 표들은 전부 **프로젝션**이다(ADR-051). 규칙 셋이 모양을 정했다:
--   1. 어느 토픽이 행을 먼저 만들지 모른다 — 그래서 키 말고는 전부 NULL 허용이다.
--      NOT NULL 을 걸 수 있는 칸이 없다: 그 칸을 싣는 사실이 아직 안 왔을 수 있다.
--   2. 부재는 값이 아니다 — DEFAULT 를 두지 않는다. DEFAULT 0 / false 는 아직 아무도
--      하지 않은 주장을 행이 만들어지는 순간 적는다.
--   3. 서비스 간 FK 는 없다(불변규칙 3). 표끼리도 FK 를 걸지 않는다 — 라우트 행보다
--      주문 행의 route_id 가 먼저 올 수 있고, 그 순서는 정상이다.
--
-- 인덱스는 §5.5 에 적힌 것만 만든다 (CLAUDE.md 불변규칙 11).
-- =============================================================================

-- --- 주문 -----------------------------------------------------------------------
--
-- 일곱 토픽이 쓴다(§5.5 「DDL 정정」). 칸마다 쓰는 토픽이 하나다 — 키를 이루는 불변 속성이
-- 없는 표라 예외도 없다.

CREATE TABLE rm_orders (
  order_id              UUID PRIMARY KEY,
  customer_id           UUID,                  -- order.placed
  service_tier          VARCHAR(16),           -- order.placed
  -- 주문 쪽 축. PLACED → PLANNED → UNSERVICEABLE → DISPATCHED → CANCELLED (전순서, §5.5).
  order_status          VARCHAR(16) CHECK (order_status IN
                          ('PLACED', 'PLANNED', 'UNSERVICEABLE', 'DISPATCHED', 'CANCELLED')),
  -- tracking 의 결과. delivery.status 만 쓴다. 결과가 아직 없으면 NULL 이다 — 'NONE' 이 아니다.
  delivery_outcome      VARCHAR(16) CHECK (delivery_outcome IN ('FAILED', 'COMPLETED')),
  camp_id               UUID,                  -- fulfillment.planned
  wave_id               UUID,                  -- fulfillment.planned
  route_id              UUID,                  -- route.assigned (planned_as_of 로 거른다)
  promised_end_original TIMESTAMPTZ,           -- order.placed
  promised_end_revised  TIMESTAMPTZ,           -- fulfillment.planned
  planned_arrival       TIMESTAMPTZ,           -- route.assigned — 계획, 언제나
  planned_as_of         TIMESTAMPTZ,           -- 위 두 칸을 쓴 route.assigned 의 occurredAt
  eta_at                TIMESTAMPTZ,           -- delivery.at-risk — 개정됐을 때만
  eta_as_of             TIMESTAMPTZ,           -- 위 칸을 쓴 at-risk 의 detectedAt
  delivered_at          TIMESTAMPTZ,           -- delivery.status(COMPLETED) 의 occurredAt
  -- 생성 칸: 입력이 세 토픽에서 오므로 핸들러가 계산하면 「마지막으로 온 것」만 맞는다.
  -- 쓰는 사람이 없는 칸은 순서를 탈 수 없다. FAILED 는 약속과 무관하게 false.
  on_time_promised      BOOLEAN GENERATED ALWAYS AS (CASE
                          WHEN delivery_outcome = 'FAILED' THEN false
                          WHEN delivery_outcome = 'COMPLETED' THEN delivered_at <= promised_end_original
                        END) STORED,
  on_time_revised       BOOLEAN GENERATED ALWAYS AS (CASE
                          WHEN delivery_outcome = 'FAILED' THEN false
                          WHEN delivery_outcome = 'COMPLETED' THEN delivered_at <= promised_end_revised
                        END) STORED,
  -- 사실이 아니라 프로젝션의 기록 — 마지막으로 행을 만진 시각이라 정의상 처리 순서를 탄다.
  updated_at            TIMESTAMPTZ
);

COMMENT ON TABLE rm_orders IS
  '주문 읽기 모델. 행은 먼저 온 사실이 만들고 늦게 온 사실이 채운다 — 빈 칸은 「없음」이 아니라 「아직」이다(ADR-051).';
COMMENT ON COLUMN rm_orders.planned_as_of IS
  '주문의 계획 칸(route_id·planned_arrival)은 라우트를 넘어 견줘야 한다 — revision 은 라우트마다 독립이라 '
  '견줄 수 없고(ADR-045), 계획을 내는 것은 dispatch 하나이므로 그 발행 시각으로 견준다(§5.5).';

-- --- 웨이브 ---------------------------------------------------------------------

CREATE TABLE rm_waves (
  wave_id          UUID PRIMARY KEY,
  -- 웨이브 키(§5.2). 여러 토픽이 사본을 싣고 값이 같다 — 먼저 온 것이 쓰고 덮지 않는다.
  camp_id          UUID,
  service_tier     VARCHAR(16),
  cutoff_at        TIMESTAMPTZ,
  -- OPEN → CLOSED → PLAN_FAILED → PLANNED. CLOSING 은 없다 — 그것을 싣는 이벤트가 없다.
  status           VARCHAR(16) CHECK (status IN ('OPEN', 'CLOSED', 'PLAN_FAILED', 'PLANNED')),
  order_count      INTEGER,                   -- 집계(rm_orders.wave_id) — 증감이 아니다(ADR-051 결정 4)
  plan_id          UUID,                      -- plan.completed
  plan_duration_ms INTEGER,                   -- plan.completed
  total_cost_krw   BIGINT,                    -- plan.completed (불변규칙 9)
  unassigned_count INTEGER,                   -- plan.completed
  route_count      INTEGER                    -- plan.completed 의 routeCount — 기대치(ADR-024)
);

-- --- 라우트 ---------------------------------------------------------------------

CREATE TABLE rm_routes (
  route_id          UUID PRIMARY KEY,
  plan_id           UUID,                     -- route.assigned (revision 으로 거른다)
  camp_id           UUID,                     -- 라우트의 키 속성 — 사본을 싣는 토픽 모두가 쓴다
  vehicle_id        UUID,
  driver_id         UUID,
  revision          INTEGER CHECK (revision >= 1),   -- §6.8 4단계의 비교 칸
  -- ASSIGNED → DEPARTED. delivery.status 도 DEPARTED 를 쓴다 — 배송이 있었다면 출발한 것이다.
  status            VARCHAR(16) CHECK (status IN ('ASSIGNED', 'DEPARTED')),
  planned_departure TIMESTAMPTZ,              -- route.assigned 의 summary.plannedDeparture
  departed_at       TIMESTAMPTZ,              -- delivery.route-departed 의 departedAt
  stop_count        INTEGER,
  completed_count   INTEGER,                  -- 집계(rm_orders.route_id · delivery_outcome)
  failed_count      INTEGER,                  -- 집계
  -- at-risk 가 온 적이 있다. 오기 전에는 NULL — false 는 「위험하지 않다」라는 주장이다.
  at_risk           BOOLEAN,
  distance_m        INTEGER,
  cost_krw          INTEGER                   -- 불변규칙 9
);

-- --- KPI 시간 버킷 (채우는 것은 묶음 B 의 KPI 단계) -----------------------------

CREATE TABLE rm_kpi_hourly (
  camp_id     UUID NOT NULL,
  bucket_hour TIMESTAMPTZ NOT NULL,
  orders      INTEGER,
  dispatched  INTEGER,
  delivered   INTEGER,
  on_time     INTEGER,
  late        INTEGER,
  failed      INTEGER,
  cost_krw    BIGINT,
  PRIMARY KEY (camp_id, bucket_hour)
);

-- --- 감사 기록 (§5.5 — 모든 커맨드) ------------------------------------------

CREATE TABLE audit_logs (
  id          UUID PRIMARY KEY,               -- UUIDv7 (불변규칙 10)
  actor       VARCHAR(64) NOT NULL,
  action      VARCHAR(48) NOT NULL,
  target_type VARCHAR(24) NOT NULL,
  target_id   UUID,
  request     JSONB,
  result      VARCHAR(16) NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL
);
