-- =============================================================================
-- tracking-service 스키마 (DESIGN.md §5.4)
--
-- 공통 테이블(outbox_events, processed_events)은 libs/messaging 이 클래스패스로 주는
-- V000_x 스크립트가 만든다. 여기에는 이 서비스 고유의 것만 둔다.
--
-- 인덱스는 §5.4 DDL 에 명시된 것만 만든다 (CLAUDE.md 불변규칙 11).
-- =============================================================================

-- --- 배송 ---------------------------------------------------------------------
--
-- order_id 가 PK 다. stop 은 여러 주문을 묶지만(§6.5 1단계 StopMerger) 배송의 성공·실패와
-- 정시 여부는 주문마다 답해야 하는 질문이다. 같은 주문이 두 번 와도 한 행이라는 점에서
-- dispatch_candidates 와 같은 멱등 장치이기도 하다(불변규칙 2 의 processed_events 와 두 겹).

CREATE TABLE shipments (
  order_id        UUID PRIMARY KEY,
  -- dispatch 의 routes.id 를 값으로만 들고 있다. 서비스 간 FK 는 불변규칙 3 이 금지한다.
  route_id        UUID NOT NULL,
  stop_seq        SMALLINT NOT NULL CHECK (stop_seq >= 1),
  -- SCHEDULED | OUT_FOR_DELIVERY | ARRIVED | COMPLETED | FAILED | CANCELLED (§5.4 상태 머신).
  -- 전이는 애그리거트 메서드로만 한다 (불변규칙 6).
  status          VARCHAR(20) NOT NULL,
  -- 아래 세 칸이 NOT NULL 인 것은 계약이 정했다: route.assigned.v1 의 plannedArrival 과
  -- promisedWindow 가 둘 다 required 이고(Phase 5-1a), 이 표를 채우는 경로는 그 이벤트뿐이다.
  planned_arrival TIMESTAMPTZ NOT NULL,
  eta_at          TIMESTAMPTZ NOT NULL,
  promised_end    TIMESTAMPTZ NOT NULL,
  delivered_at    TIMESTAMPTZ,
  version         BIGINT NOT NULL DEFAULT 0
);

-- §5.4 DDL 명시. "이 라우트의 stop 을 순서대로" 가 ETA 재전파와 at-risk 판정의 유일한 경로다.
CREATE INDEX ix_ship_route ON shipments (route_id, stop_seq);

COMMENT ON COLUMN shipments.promised_end IS
  'route.assigned 의 promisedWindow.end. at-risk 기준(eta > promised_end − 15분, §5.4)이다. '
  '이 값이 원 약속인지 개정된 약속인지 tracking 은 모른다 — 두 기준 정시율은 ops-api 가 낸다(§5.5).';
COMMENT ON COLUMN shipments.eta_at IS
  '처음에는 planned_arrival 과 같고, 앞 stop 의 편차 d 만큼 밀린다(§5.4 ETA 재계산).';

-- --- 개정 비교 ----------------------------------------------------------------
--
-- §8.5 의 "routeId + revision". 비교는 라우트 단위다 — shipments 에서 MAX 로 유도하면
-- relocate 가 라우트를 비웠을 때 비교할 값이 사라지고, shipment 행마다 비교하면 라우트마다
-- 독립인 번호를 라우트 밖에서 견주게 된다(§5.4 의 버린 대안 둘).

CREATE TABLE route_revisions (
  route_id   UUID PRIMARY KEY,
  revision   INTEGER NOT NULL CHECK (revision >= 1),   -- 최초 확정이 1 (§6.8 4단계)
  -- camp 는 라우트의 성질이고 at-risk 도 라우트 단위라 여기가 그 자리다(§9.1
  -- dawnline_at_risk_total{camp}). shipments 에 두면 주문 단위 표에 라우트 속성을
  -- 비정규화하는 것이 된다.
  camp_id    UUID NOT NULL,
  applied_at TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE route_revisions IS
  'route.assigned 를 라우트당 마지막으로 적용한 개정. 이보다 낮거나 같은 revision 은 무시한다(§6.8 4단계).';
COMMENT ON COLUMN route_revisions.camp_id IS
  'route.assigned.v1 의 campId(required). NOT NULL 인 것은 그 계약이 정했다 — NULL 이 올 경로가 '
  '없는 칸을 널 허용으로 두면 메트릭 라벨에 「캠프를 모르는 라우트」 분기가 생기고, 그 분기는 '
  '한 번도 실행되지 않으면서 리뷰마다 읽힌다(§5.4 의 세 칸과 같은 이유).';

-- --- 스캔 이벤트 (일 파티션, 보존 30일) ---------------------------------------
--
-- 인덱스는 PK 뿐이다. 넣지 않기로 한 판단을 행 수와 함께 남긴다(불변규칙 11):
-- peak 기준 주문 15,000건/일 × 스캔 4종 = 파티션당 6만 행이고, Phase 5 에서 이 표를 읽는
-- 질의는 없다 — shipments 가 현재 상태를 들고 있고 ops 화면은 Phase 6 의 읽기 모델이 답한다.
-- 6만 행 한 파티션의 순차 스캔은 조사용 일회성 질의에 충분하다. 읽는 경로가 생기면 그때
-- EXPLAIN 과 함께 다시 본다.

CREATE TABLE shipment_events (
  id          UUID NOT NULL,                           -- UUIDv7 (불변규칙 10)
  order_id    UUID NOT NULL,
  route_id    UUID NOT NULL,
  type        VARCHAR(20) NOT NULL,                    -- DEPARTED_CAMP | ARRIVED | COMPLETED | FAILED
  occurred_at TIMESTAMPTZ NOT NULL,                    -- 파티션 키. 기사 단말의 사건 시각
  lat         NUMERIC(9,6),                            -- 불변규칙 9
  lng         NUMERIC(9,6),
  payload     JSONB,
  -- 파티션 키가 PK 에 들어가야 한다. id 단독 PK 는 파티션 테이블에서 만들 수 없다.
  PRIMARY KEY (occurred_at, id)
) PARTITION BY RANGE (occurred_at);

COMMENT ON TABLE shipment_events IS
  '기사 스캔 원장 (§5.4). 일 단위 파티션, 보존 30일. DEFAULT 파티션을 두지 않는다 — '
  '범위 밖 행이 조용히 쌓이면 그 날짜의 파티션 생성이 며칠 뒤에 실패한다.';

-- --- 파티션 생성·삭제 ---------------------------------------------------------
--
-- pg_partman 을 쓰지 않는다(§5.4). 이름 규칙(shipment_events_YYYYMMDD)과 경계 계산은
-- **이 두 함수에만** 있고, 자바 스케줄러는 주입된 시계에서 뽑은 날짜를 넘길 뿐이다
-- (불변규칙 12). 이름을 양쪽에서 만들면 규칙이 두 곳이 되고 둘은 갈라진다.

CREATE FUNCTION tracking_ensure_event_partitions(p_from DATE, p_days INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
  v_day     DATE;
  v_name    TEXT;
  v_created INTEGER := 0;
BEGIN
  IF p_from IS NULL THEN
    RAISE EXCEPTION 'p_from 은 NULL 일 수 없습니다';
  END IF;
  IF p_days IS NULL OR p_days < 1 THEN
    RAISE EXCEPTION 'p_days 는 1 이상이어야 합니다 (받은 값: %)', p_days;
  END IF;

  FOR i IN 0 .. p_days - 1 LOOP
    v_day  := p_from + i;
    v_name := 'shipment_events_' || to_char(v_day, 'YYYYMMDD');
    IF to_regclass(v_name) IS NULL THEN
      BEGIN
        -- 경계는 세션 타임존과 무관하게 UTC 자정이다. occurred_at 이 TIMESTAMPTZ 이므로
        -- 경계를 세션 타임존으로 만들면 같은 스크립트가 환경마다 다른 날을 가른다.
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF shipment_events FOR VALUES FROM (%L) TO (%L)',
            v_name,
            (v_day::TIMESTAMP AT TIME ZONE 'UTC'),
            ((v_day + 1)::TIMESTAMP AT TIME ZONE 'UTC'));
        v_created := v_created + 1;
      EXCEPTION WHEN duplicate_table THEN
        -- 다른 인스턴스가 먼저 만들었다. 만드는 것이 목적이지 내가 만드는 것이 목적이 아니다.
        NULL;
      END;
    END IF;
  END LOOP;

  RETURN v_created;
END;
$$;

COMMENT ON FUNCTION tracking_ensure_event_partitions(DATE, INTEGER) IS
  'p_from 부터 p_days 일치의 일 파티션을 만든다. 이미 있으면 건너뛴다. 반환값은 실제로 만든 수.';

CREATE FUNCTION tracking_drop_event_partitions(p_before DATE)
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
  v_name    TEXT;
  v_dropped INTEGER := 0;
BEGIN
  IF p_before IS NULL THEN
    RAISE EXCEPTION 'p_before 는 NULL 일 수 없습니다';
  END IF;

  FOR v_name IN
    SELECT c.relname
      FROM pg_inherits i
      JOIN pg_class c ON c.oid = i.inhrelid
     WHERE i.inhparent = 'shipment_events'::regclass
       AND c.relname ~ '^shipment_events_[0-9]{8}$'
       AND to_date(right(c.relname, 8), 'YYYYMMDD') < p_before
     ORDER BY c.relname
  LOOP
    EXECUTE format('DROP TABLE %I', v_name);
    v_dropped := v_dropped + 1;
  END LOOP;

  RETURN v_dropped;
END;
$$;

COMMENT ON FUNCTION tracking_drop_event_partitions(DATE) IS
  'p_before 이전 날짜의 일 파티션을 지운다 (§5.4 보존 30일). 반환값은 지운 수.';

CREATE FUNCTION tracking_last_event_partition()
RETURNS DATE
LANGUAGE sql
STABLE
AS $$
  SELECT max(to_date(right(c.relname, 8), 'YYYYMMDD'))
    FROM pg_inherits i
    JOIN pg_class c ON c.oid = i.inhrelid
   WHERE i.inhparent = 'shipment_events'::regclass
     AND c.relname ~ '^shipment_events_[0-9]{8}$';
$$;

COMMENT ON FUNCTION tracking_last_event_partition() IS
  '가장 늦은 파티션의 날짜. dawnline_shipment_partitions_ahead 게이지가 이 값에서 오늘을 뺀다(§9.1).';

-- 기동 직후에도 스캔을 받을 수 있도록 어제부터 9일치를 미리 만든다(빈 파티션뿐이다).
-- 스케줄러 기본값(앞으로 7일)과 같은 창이고, 어제를 포함하는 이유는 자정 직후에 도착하는
-- 늦은 스캔(occurred_at 이 어제)이 그 자리에서 실패하지 않게 하려는 것이다.
-- 여기서 CURRENT_DATE 를 쓰는 것은 불변규칙 12 의 예외가 아니다 — 마이그레이션은 서비스
-- 코드가 아니고, 이 호출이 만드는 것은 값이 아니라 빈 파티션이다. 이후의 모든 생성은
-- 주입된 시계를 쓰는 스케줄러가 한다.
SELECT tracking_ensure_event_partitions(CURRENT_DATE - 1, 9);
