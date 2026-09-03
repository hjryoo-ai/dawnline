-- =============================================================================
-- order-service 스키마 (DESIGN.md §5.1)
--
-- 공통 테이블(outbox_events, processed_events)은 libs/messaging 이 클래스패스로 주는
-- V000_x 스크립트가 만든다. 여기에는 이 서비스 고유의 것만 둔다.
--
-- 인덱스는 §5.1 DDL 에 명시된 것만 만든다 (CLAUDE.md 불변규칙 11).
-- =============================================================================

CREATE TABLE orders (
  id               UUID PRIMARY KEY,                 -- UUIDv7 (불변규칙 10)
  customer_id      UUID NOT NULL,
  service_tier     VARCHAR(16) NOT NULL,
  status           VARCHAR(16) NOT NULL,
  address_line     TEXT NOT NULL,
  postal_code      VARCHAR(10) NOT NULL,
  lat              NUMERIC(9,6) NOT NULL,            -- 불변규칙 9
  lng              NUMERIC(9,6) NOT NULL,
  geohash7         CHAR(7) NOT NULL,
  promised_start   TIMESTAMPTZ NOT NULL,
  promised_end     TIMESTAMPTZ NOT NULL,
  weight_g         INTEGER NOT NULL,
  volume_cm3       INTEGER NOT NULL,
  requires_cold    BOOLEAN NOT NULL DEFAULT FALSE,
  hazmat           BOOLEAN NOT NULL DEFAULT FALSE,
  version          BIGINT NOT NULL DEFAULT 0,        -- 낙관적 락
  placed_at        TIMESTAMPTZ NOT NULL,
  updated_at       TIMESTAMPTZ NOT NULL
);

-- 고객의 주문 목록 (GET /api/v1/orders?customerId, 커서 페이지네이션).
CREATE INDEX ix_orders_customer_placed ON orders (customer_id, placed_at DESC);
-- 상태별 조회 (운영 화면·정체 감지).
CREATE INDEX ix_orders_status_placed   ON orders (status, placed_at);

CREATE TABLE order_items (
  order_id UUID NOT NULL REFERENCES orders(id),
  line_no  SMALLINT NOT NULL,
  sku      VARCHAR(32) NOT NULL,
  qty      INTEGER NOT NULL CHECK (qty > 0),
  PRIMARY KEY (order_id, line_no)
);

-- 멱등 처리 (§5.1 "멱등 처리 흐름"). Redis 가 1차, 이 테이블이 진실이다 —
-- Redis 키가 사라져도 정확성이 유지되어야 한다 (불변규칙 7).
CREATE TABLE idempotency_keys (
  idem_key      VARCHAR(64) PRIMARY KEY,
  request_hash  CHAR(64) NOT NULL,                   -- SHA-256(body) 를 16진수로
  status        VARCHAR(12) NOT NULL,                -- IN_PROGRESS | DONE
  response_code SMALLINT,
  response_body JSONB,
  created_at    TIMESTAMPTZ NOT NULL,
  expires_at    TIMESTAMPTZ NOT NULL
);

COMMENT ON COLUMN orders.geohash7 IS '배송지 좌표의 7자리 geohash. §4.5 파티션 키가 아니라(그건 주문 id 다) §6.2 stop 통합·거리 캐시의 키다. 좌표에서 재계산할 수 있지만 geohash 구현이 바뀌어도 이미 발행된 이벤트와 어긋나지 않도록 보관한다.';
COMMENT ON COLUMN orders.version IS '낙관적 락 버전. 증가는 영속화 계층이 한다 — 도메인이 올리면 저장하지 않은 변경에도 버전이 움직여 충돌 판정이 어긋난다.';
COMMENT ON COLUMN idempotency_keys.request_hash IS '같은 키에 다른 본문이 오면 422 로 거부하기 위한 해시 (§5.1 2단계).';
COMMENT ON COLUMN idempotency_keys.expires_at IS '보관 만료. Redis 키의 TTL 24h 와 별개로 DB 쪽 정리 기준이다.';
