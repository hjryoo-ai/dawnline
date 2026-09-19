-- =============================================================================
-- route_revisions 에 계획 출발 시각 (DESIGN.md §5.4, Phase 5-1b)
--
-- DEPARTED_CAMP 스캔의 편차는 d = 실제 출발 − 계획 출발 이고, 그 기준값은 스캔이 오는
-- 시점에 이미 저장돼 있어야 한다. 출처는 route.assigned.v1 의 summary.plannedDeparture
-- (required, 2026-09-19) 뿐이다.
--
-- 라우트당 한 행인 이 표가 그 자리다 — camp_id 와 같은 이유로 shipments 가 아니다.
-- 개정마다 갱신된다: 재계획은 출발 시각도 다시 정한다.
--
-- NOT NULL 인 이유는 §5.4 의 세 칸과 같다. 계약이 required 라 NULL 이 올 경로가 없고,
-- 널 허용으로 두면 「출발 시각을 모르는 라우트」 분기가 생긴다 — 그 분기의 답이 없다.
-- 늦은 출발을 감지하지 못하는 것은 조용한 열화이지 처리된 예외가 아니다.
--
-- 기존 행이 있으면 아래 블록이 먼저 멈춘다. 그 메시지가 원인이자 해법이다 — 이 표는
-- route.assigned 의 투영이고, tracking 은 아직 어떤 배포 환경에도 없다(5-1a 운영 메모:
-- 개발 볼륨의 이벤트조차 재생성 대상이다).
-- =============================================================================

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM route_revisions) THEN
    RAISE EXCEPTION '%', concat(
      'route_revisions 에 5-1b 이전 행이 있습니다. planned_departure 는 route.assigned 에서만 ',
      '오는 값이라 지어낼 수 없습니다. 이 표는 그 이벤트의 투영이므로, 이 개발 볼륨의 tracking ',
      'DB 를 비우고(또는 볼륨을 재생성하고) 토픽을 다시 읽으십시오 ',
      '— contracts/events/README.md §5 운영 메모와 같은 조치입니다.');
  END IF;
END $$;

ALTER TABLE route_revisions
  ADD COLUMN planned_departure TIMESTAMPTZ NOT NULL;

COMMENT ON COLUMN route_revisions.planned_departure IS
  'route.assigned.v1 의 summary.plannedDeparture. DEPARTED_CAMP 편차의 기준이다 (§5.4).';
