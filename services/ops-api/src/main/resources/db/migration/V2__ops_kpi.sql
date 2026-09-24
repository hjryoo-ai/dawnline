-- =============================================================================
-- ops-api KPI — 시간 버킷은 표가 아니라 뷰다 (DESIGN.md §5.5 「KPI — 두 축, 뷰」)
--
-- V1 의 rm_kpi_hourly 는 이벤트가 증감하는 표였다. ADR-051 결정 4(개수는 증감이 아니라 집계)의
-- 가장 순수한 형태는 **쓰는 쪽이 없는 것**이다 — 쓰는 핸들러가 없으면 순서 문제도 없다. 그래서
-- 그 표를 지우고 rm_orders 위의 뷰 둘로 바꾼다. 느려지면 다음 단계는 증분 쓰기가 아니라
-- materialized view 의 주기 refresh 다 — 여전히 다시 세는 것이다(§5.5).
--
-- 뷰가 둘인 이유: 행의 열은 하나의 시간 축을 공유한다. 접수 축(placed_at)의 주문 수와 배송 축
-- (완료·실패 시각)의 완료 수를 한 행에 두면, 코호트가 다른 두 수가 「100건 중 80건 배송」처럼
-- 읽힌다.
--
-- 버킷은 UTC 시각이다(§5.4 의 파티션 경계와 같은 이유 — 세션 존에 따라 움직이지 않는다).
-- 세 인자 date_trunc(text, timestamptz, text) 는 IMMUTABLE 이라 인덱스 식이 될 수 있고, 뷰의
-- bucket_hour 술어가 그 식 그대로 내려가 인덱스를 탄다 — **뷰의 식과 인덱스의 식은 글자 그대로
-- 같아야 한다**(KpiViewsIndexIT 가 계획의 Index Cond 로 대조한다).
-- =============================================================================

-- --- rm_orders 에 칸 둘 --------------------------------------------------------

ALTER TABLE rm_orders
  ADD COLUMN placed_at TIMESTAMPTZ,   -- order.placed 의 placedAt — 접수 축의 시각
  ADD COLUMN failed_at TIMESTAMPTZ,   -- delivery.status(FAILED) 의 occurredAt — delivered_at 과 대칭
  -- 추적 축에서 COMPLETED 와 FAILED 는 둘 다 종결이라 한 주문이 둘을 다 갖지 않는다. 배송 축의
  -- 버킷 COALESCE(delivered_at, failed_at) 은 이 배타성 위에서만 옳다 — 그래서 문장이 아니라
  -- 제약으로 둔다. 재배송이 들어와 FAILED 뒤 COMPLETED 가 생기면 여기서 크게 깨진다(§5.5 재검토 조건).
  ADD CONSTRAINT ck_rmo_outcome_time_exclusive CHECK (NOT (delivered_at IS NOT NULL AND failed_at IS NOT NULL));

COMMENT ON COLUMN rm_orders.failed_at IS
  '배송 실패 시각 — delivery_outcome 과 같은 추적 축이 판정하고, 축이 FAILED 로 옮길 때만 쓴다. '
  'delivered_at 과 배타(ck_rmo_outcome_time_exclusive).';

-- --- 증감 표를 지운다 ----------------------------------------------------------

DROP TABLE rm_kpi_hourly;

-- --- 접수 축 -------------------------------------------------------------------
--
-- 배차 불가 주문은 캠프가 없다(§4.3) — 그래서 unserviceable 은 camp_id 가 NULL 인 행에만 있다.
-- 그 행의 orders 에는 fulfillment.planned 가 아직 오지 않은 주문도 들어 있다(「아직」).

CREATE VIEW kpi_intake_hourly AS
SELECT camp_id,
       date_trunc('hour', placed_at, 'UTC') AS bucket_hour,
       count(*) AS orders,
       count(*) FILTER (WHERE order_status = 'UNSERVICEABLE') AS unserviceable
  FROM rm_orders
 WHERE placed_at IS NOT NULL
 GROUP BY camp_id, date_trunc('hour', placed_at, 'UTC');

-- --- 배송 축 -------------------------------------------------------------------
--
-- 한 행의 모집단: 결과·캠프·두 약속을 모두 아는, 취소되지 않은 주문. 약속을 아직 모르는 결과는
-- 분모에도 분자에도 없다 — 「모름」을 「늦음」으로 세지 않는다(ADR-051 결정 2). 캠프는 개정 약속과
-- 같은 사실(fulfillment.planned)로 오므로 캠프를 모르는 것은 약속을 모르는 것의 한 형태다.
-- **그러나 빠진 수는 보인다** — outcome_without_promise. 분모에서 조용히 빠지는 것은 실패를 빼서
-- 정시율을 올리는 것과 같은 부류다. 부재는 값이 아니지만 부재의 수는 값이다. 정상에서 이 수는
-- 프로젝션 랙만큼의 일시값이고, 계속 0 이 아니면 fulfillment.planned(또는 order.placed)가 오지
-- 않고 있다. 캠프를 모르는 결과는 camp_id 가 NULL 인 행에 있다(접수 축의 배차 불가와 같은 모양).
-- 취소된 주문은 배송됐어도 빠지고 여기서 세지도 않는다 — 모름이 아니라 정의다(약속이 더는 서 있지
-- 않다). 그것은 예외 목록의 행이다.
-- FAILED 는 분모에 있고 분자에 없다: on_time_* 이 FAILED 에서 false 다(V1 의 생성 칸).
-- late 는 두지 않는다 — delivered + failed − on_time_* 로 유도된다.
-- revised 는 완료된 주문 중 약속의 끝이 개정된 수 — 두 정시율의 격차가 몇 건의 개정에서 왔는가.
-- 안쪽 질의는 모집단 판정(known)을 한 번만 적으려는 것이고, 플래너가 끌어올려 bucket_hour 술어는
-- 여전히 인덱스의 식 그대로 내려간다(KpiViewsIndexIT).

CREATE VIEW kpi_delivery_hourly AS
SELECT camp_id,
       bucket_hour,
       count(*) FILTER (WHERE known AND delivery_outcome = 'COMPLETED') AS delivered,
       count(*) FILTER (WHERE known AND delivery_outcome = 'FAILED') AS failed,
       count(*) FILTER (WHERE known AND on_time_promised) AS on_time_promised,
       count(*) FILTER (WHERE known AND on_time_revised) AS on_time_revised,
       count(*) FILTER (WHERE known AND delivery_outcome = 'COMPLETED'
                          AND promised_end_revised <> promised_end_original) AS revised,
       count(*) FILTER (WHERE NOT known) AS outcome_without_promise
  FROM (SELECT camp_id,
               date_trunc('hour', COALESCE(delivered_at, failed_at), 'UTC') AS bucket_hour,
               delivery_outcome, on_time_promised, on_time_revised,
               promised_end_original, promised_end_revised,
               (camp_id IS NOT NULL
                AND promised_end_original IS NOT NULL
                AND promised_end_revised IS NOT NULL) AS known
          FROM rm_orders
         WHERE delivery_outcome IS NOT NULL
           AND order_status <> 'CANCELLED') o
 GROUP BY camp_id, bucket_hour;

-- --- 인덱스 둘 — 두 뷰의 버킷 식 그대로 (§5.5 명시분) ----------------------------
-- 측정: docs/benchmarks/phase6-kpi-hourly-views-index.md. peak 30일(450만 행)에서 캠프 하나 24 버킷:
-- 배송 축 212.2 → 6.38 ms, 접수 축 187.4 → 4.98 ms, 게이지(전 캠프 24 버킷) 474 → 39.6 ms.

CREATE INDEX ix_rmo_delivery_hour ON rm_orders (camp_id, date_trunc('hour', COALESCE(delivered_at, failed_at), 'UTC'));
CREATE INDEX ix_rmo_intake_hour   ON rm_orders (camp_id, date_trunc('hour', placed_at, 'UTC'));
