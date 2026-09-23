-- ---------------------------------------------------------------------------
-- route_stops.status 의 값 목록에 FAILED 를 더한다 (DESIGN.md §5.3, Phase 5-5, ADR-047).
--
-- 스키마는 바뀌지 않는다 — 컬럼은 VARCHAR(16) 이고 CHECK 제약이 없어서 값은 이미 들어간다.
-- 바뀌는 것은 **주석**이다. V1 은 네 값(PLANNED|CANCELLED|ARRIVED|COMPLETED)만 적고 있고,
-- 머지된 V 스크립트는 주석 한 글자도 고치지 않으므로(불변규칙 13) 여기서 다시 적는다.
--
-- 주석 하나를 위해 마이그레이션을 하나 쓰는 것이 과해 보이지만, 그 대안은 «틀린 주석을 그대로
-- 두기» 다. 스키마 주석은 psql \d+ 로 읽히는 문서이고, 거기에 없는 값이 테이블에 들어 있으면
-- 다음 사람은 그것을 데이터 오염으로 읽는다.
--
-- CHECK 제약을 더하지 않는 이유: §4.7 이 같은 major 안에서 enum 값 추가를 허용한다. 제약을
-- 걸면 delivery.status.v1 에 값이 하나 늘 때마다 마이그레이션이 필요해지고, 그 사이의 이벤트는
-- DB 오류가 된다. 모르는 값은 리스너가 무시하고 센다(dawnline_event_rejected_total).
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN route_stops.status IS
  'PLANNED | CANCELLED | ARRIVED | COMPLETED | FAILED. '
  'CANCELLED 는 취소된 주문의 stop 이고 페이로드에서도 지우지 않는다 (ADR-026 — 부재는 값이 아니다). '
  '뒤의 셋은 delivery.status 소비가 옮긴다 (ADR-047): 진행 축은 PLANNED → ARRIVED → COMPLETED/FAILED '
  '이고 CANCELLED 는 축 밖이다. FAILED 도 종결이라 §6.8 의 부분 재계획이 다시 배정하지 않는다.';
