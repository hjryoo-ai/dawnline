-- =============================================================================
-- fulfillment_orders 도입 · wave_orders 드롭 (ADR-022, DESIGN.md §5.2)
--
-- V1 은 이미 main 에 있으므로 고치지 않는다 (불변규칙 13, 예외 없음).
-- 방금 만든 빈 테이블을 지우는 마이그레이션이 이력에 남고, 그것이 정직한 이력이다.
--
-- wave_orders 가 담지 못한 것:
--   * 배차 불가(UNSERVICEABLE) — 웨이브가 없으니 복합 PK (wave_id, order_id) 에 행을 만들 수 없다
--   * 취소 — "행이 없음" 과 구별되지 않는다
--   * 약속 개정 — 담을 컬럼이 없다
-- 셋 다 "주문 X 는 왜 웨이브에 없나" 의 답인데, 그 답이 전부 같은 값(행 없음)이었다.
-- =============================================================================

-- 주문 하나당 행 하나. order_id 단독 PK 라 "한 주문이 두 웨이브" 가 구조적으로 불가능하다 —
-- wave_orders 의 복합 PK 는 "한 웨이브 안의 중복" 만 막았다.
CREATE TABLE fulfillment_orders (
  order_id             UUID PRIMARY KEY,
  -- PLANNED | UNSERVICEABLE | CANCELLED (FulfillmentOrderStatus)
  --
  -- CHECK 를 걸지 않는다. 상태 전이는 애그리거트 메서드가 강제하고(불변규칙 6), DB 제약은 그
  -- 규칙의 일부만 복제해 두 곳이 어긋날 여지를 만든다. orders 테이블도 같다.
  status               VARCHAR(16) NOT NULL,
  wave_id              UUID REFERENCES waves(id),     -- status=PLANNED 일 때만 채운다
  camp_id              UUID,
  fc_id                UUID,
  zone_id              UUID,
  -- order.placed 가 싣고 온 값. 여기서 다시 계산하지 않는다 (ADR-020).
  cutoff_at            TIMESTAMPTZ,
  promised_start       TIMESTAMPTZ,
  promised_end         TIMESTAMPTZ,
  -- 위 약속창이 접수 시점의 것과 다른가. fulfillment.planned 를 타고 order-service 로 돌아가
  -- 고객의 약속을 갱신한다 — 조용히 깨지 않기 위한 경로다 (ADR-020 결정 3).
  promise_revised      BOOLEAN NOT NULL DEFAULT FALSE,
  unserviceable_reason VARCHAR(24),
  fc_fallback_reason   VARCHAR(16),
  -- NULL 이면 order.placed 가 아직 오지 않았다 = 취소 선착 (ADR-022 결정 3).
  -- 별도 마커 테이블이 필요 없는 이유가 이 컬럼 하나다.
  placed_event_id      UUID,
  cancelled_at         TIMESTAMPTZ,
  version              BIGINT NOT NULL DEFAULT 0,     -- 낙관적 락
  created_at           TIMESTAMPTZ NOT NULL,
  updated_at           TIMESTAMPTZ NOT NULL
);

-- 웨이브 마감 시 후보를 모으는 조회 (ADR-022 결정 5 + Phase 2-4 후속 정정).
--
-- ADR-022 는 부분 인덱스(WHERE status='PLANNED')를 골랐는데, 측정이 그 결정을 뒤집었다.
--   1) 정상 상태의 98% 가 PLANNED 라 부분 조건이 거르는 게 2% 뿐이다 (부분 31 MB / 전체 32 MB —
--      wave_id 는 중복 제거가 잘 드는 컬럼이다).
--   2) 부분 인덱스는 FK 검사에 쓰이지 못한다. 플래너가 술어(status='PLANNED')로 검사(모든 상태)를
--      덮을 수 있음을 증명하지 못하므로, waves 를 지울 때마다 4.65M 행을 순차 스캔한다 —
--      90일 정리에서 40건 삭제에 FK 트리거만 6.7초였다(전체 인덱스는 0.57 ms).
-- EXPLAIN 근거: docs/benchmarks/phase2-fulfillment-orders-indexes.md §3
CREATE INDEX ix_fulfillment_orders_wave ON fulfillment_orders (wave_id);

-- 보존 30일 정리 배치의 범위 스캔 (ADR-023 결정 1, 불변규칙 11).
-- 삭제 조건이 updated_at < now() - 30d 이고 PK 는 order_id 라 이 범위를 돕지 못한다.
-- created_at 이 아니라 updated_at 인 이유: 접수는 30일 전이라도 취소·약속 개정이 어제면
-- 조사 대상은 어제 사건이다. created_at 으로 재면 방금 바뀐 행이 지워진다.
-- EXPLAIN 근거: docs/benchmarks/phase2-fulfillment-orders-indexes.md
CREATE INDEX ix_fulfillment_orders_cleanup ON fulfillment_orders (updated_at);

-- waves 에는 정리용 인덱스를 넣지 않는다 (불변규칙 11 — 넣지 않은 판단도 기록한다).
-- 90일치가 하루 40행 × 90 = 약 4,000행이라 순차 스캔이 인덱스보다 싸다. 이 숫자를 함께 적는
-- 이유는 캠프·티어가 늘어 규모가 바뀌었을 때 재검토 지점이 되게 하기 위해서다 (ADR-023 결정 3).

DROP TABLE wave_orders;
