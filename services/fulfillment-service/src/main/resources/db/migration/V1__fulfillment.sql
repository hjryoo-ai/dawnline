-- =============================================================================
-- fulfillment-service 스키마 (DESIGN.md §5.2)
--
-- 공통 테이블(outbox_events, processed_events)은 libs/messaging 이 클래스패스로 주는
-- V000_x 스크립트가 만든다. 여기에는 이 서비스 고유의 것만 둔다.
--
-- 인덱스는 §5.2 DDL 에 명시된 것만 만든다 (CLAUDE.md 불변규칙 11).
-- 참조 데이터(fulfillment_centers·camps·zones·inventory_stock)의 값은 R__seed_fulfillment.sql 이
-- 넣는다. 스키마와 시드를 나누는 이유는 시드가 바뀔 때 버전 스크립트를 쌓지 않기 위해서다
-- (반복 마이그레이션은 체크섬이 바뀌면 다시 돈다 — 불변규칙 13은 V__ 스크립트에 대한 것이다).
-- =============================================================================

-- --- 참조 데이터 -------------------------------------------------------------

CREATE TABLE fulfillment_centers (
  id            UUID PRIMARY KEY,                    -- UUIDv7 (불변규칙 10)
  code          VARCHAR(16) NOT NULL UNIQUE,
  name          TEXT NOT NULL,
  lat           NUMERIC(9,6) NOT NULL,               -- 불변규칙 9
  lng           NUMERIC(9,6) NOT NULL,
  supports_cold BOOLEAN NOT NULL DEFAULT FALSE,
  -- §2.2 의 티어 중 이 FC 가 처리할 수 있는 것 (§5.2 1단계 필터).
  tiers         VARCHAR(16)[] NOT NULL,
  active        BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE camps (
  id     UUID PRIMARY KEY,
  code   VARCHAR(16) NOT NULL UNIQUE,
  -- 홈 FC. 이 FC 가 §5.2 1~3단계 필터에서 떨어지면 대체 FC 를 고른다(ADR-021 결정 3).
  fc_id  UUID NOT NULL REFERENCES fulfillment_centers(id),
  name   TEXT NOT NULL,
  lat    NUMERIC(9,6) NOT NULL,
  lng    NUMERIC(9,6) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE
);

-- 권역. geohash5 하나가 권역 하나다 (부록 C — 약 4.9 km 셀).
-- 이 표가 order-service 지오코더의 출력을 전부 덮어야 한다 (ADR-021,
-- contracts/seed/order-service-geohash5.txt). 덮지 못한 셀의 주소는 UNSERVICEABLE 이 되는데
-- 그것이 설계된 실패 경로와 같은 값이라 구별되지 않는다.
CREATE TABLE zones (
  id       UUID PRIMARY KEY,
  camp_id  UUID NOT NULL REFERENCES camps(id),
  code     VARCHAR(16) NOT NULL,
  geohash5 CHAR(5) NOT NULL UNIQUE
);

-- 재고 스텁 (§5.2 3단계). 실서비스에서는 재고 서비스 연동 지점이다.
--
-- 스텁의 규칙: **행이 없으면 가용**이다. 즉 이 표는 예외(품절·소량)만 적는다.
-- 2,000개 SKU × FC 3개를 전부 적어 두면 "이 주문은 왜 OUT_OF_STOCK 인가" 의 답이 6,000행
-- 어딘가에 묻힌다. 예외만 적으면 그 답이 몇 줄이다.
CREATE TABLE inventory_stock (
  fc_id         UUID NOT NULL REFERENCES fulfillment_centers(id),
  sku           VARCHAR(32) NOT NULL,
  available_qty INTEGER NOT NULL CHECK (available_qty >= 0),
  PRIMARY KEY (fc_id, sku)
);

-- --- 웨이브 -----------------------------------------------------------------

-- 웨이브는 (campId, serviceTier, cutoffAt) 당 하나다.
-- cutoff_at 은 order.placed 가 싣고 온 값을 그대로 쓴다 — 여기서 다시 계산하지 않는다(ADR-020).
CREATE TABLE waves (
  id           UUID PRIMARY KEY,
  camp_id      UUID NOT NULL,
  service_tier VARCHAR(16) NOT NULL,
  cutoff_at    TIMESTAMPTZ NOT NULL,
  -- OPEN → CLOSING → CLOSED → PLANNED / PLAN_FAILED (§5.2 Wave 수명주기)
  status       VARCHAR(16) NOT NULL,
  order_count  INTEGER NOT NULL DEFAULT 0,
  closed_at    TIMESTAMPTZ,
  version      BIGINT NOT NULL DEFAULT 0,            -- 낙관적 락 (편입 경합 차단)
  UNIQUE (camp_id, service_tier, cutoff_at)
);

-- 컷오프 스케줄러가 30초마다 도는 조회. 부분 인덱스라 OPEN 인 웨이브만 담는다 —
-- 마감된 웨이브는 이 조회의 대상이 아니고, 그쪽이 계속 쌓이는 쪽이다.
CREATE INDEX ix_waves_open_cutoff ON waves (cutoff_at) WHERE status = 'OPEN';

CREATE TABLE wave_orders (
  wave_id  UUID NOT NULL REFERENCES waves(id),
  order_id UUID NOT NULL,
  fc_id    UUID NOT NULL,
  zone_id  UUID NOT NULL,
  added_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (wave_id, order_id)
);
