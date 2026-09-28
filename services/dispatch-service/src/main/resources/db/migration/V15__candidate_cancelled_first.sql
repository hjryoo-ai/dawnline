-- ===========================================================================
-- 후보보다 먼저 온 취소가 행을 남긴다 (DESIGN.md §5.3 · §6.10, ADR-074)
--
-- dispatch 는 후보를 fulfillment.planned 에서, 취소를 order.cancelled 에서 받고 두 토픽의 순서는 보장되지 않는다(§4.5).
-- 취소가 먼저 오면 지금까지는 버렸고, 뒤에 온 적재가 취소된 주문을 PENDING 후보로 만들었다 — turbulent 에서 계획 전
-- 취소 234건 중 12건이 배송됐다(근거: 관측). 이제 먼저 온 취소가 행을 만든다: status = 'CANCELLED', 스냅샷 칸은 NULL.
-- 뒤에 온 적재의 ON CONFLICT DO NOTHING 은 그 행을 되살리지 않는다.
--
-- 스냅샷 칸은 모른다 — false · 0 을 쓰지 않는다(ADR-051 결정 2, 부재는 값이 아니다). 그래서 기본값도 지운다: 기본값이
-- 남으면 칸을 빠뜨린 INSERT 가 false · 0 을 조용히 쓴다. 행의 모양은 CHECK 하나가 둘로 닫는다 — 스냅샷 칸이 전부 있거나,
-- CANCELLED 이고 전부 없다. zone_id 는 스냅샷 행에서도 NULL 일 수 있고(지오코딩 실패) 표식에서는 언제나 NULL 이다.
--
-- 기존 행은 전부 첫 모양이다(V1 이 열넷 모두 NOT NULL 이었다) — CHECK 는 한 번 훑고 통과한다.
-- ===========================================================================

ALTER TABLE dispatch_candidates
  ALTER COLUMN wave_id         DROP NOT NULL,
  ALTER COLUMN camp_id         DROP NOT NULL,
  ALTER COLUMN lat             DROP NOT NULL,
  ALTER COLUMN lng             DROP NOT NULL,
  ALTER COLUMN geohash7        DROP NOT NULL,
  ALTER COLUMN weight_g        DROP NOT NULL,
  ALTER COLUMN volume_cm3      DROP NOT NULL,
  ALTER COLUMN requires_cold   DROP NOT NULL,
  ALTER COLUMN requires_cold   DROP DEFAULT,
  ALTER COLUMN hazmat          DROP NOT NULL,
  ALTER COLUMN hazmat          DROP DEFAULT,
  ALTER COLUMN promised_start  DROP NOT NULL,
  ALTER COLUMN promised_end    DROP NOT NULL,
  ALTER COLUMN service_seconds DROP NOT NULL,
  ALTER COLUMN promise_revised DROP NOT NULL,
  ALTER COLUMN promise_revised DROP DEFAULT,
  ALTER COLUMN priority        DROP NOT NULL,
  ALTER COLUMN priority        DROP DEFAULT;

ALTER TABLE dispatch_candidates
  ADD CONSTRAINT ck_cand_snapshot_or_cancelled_first CHECK (
    (wave_id IS NOT NULL AND camp_id IS NOT NULL AND lat IS NOT NULL AND lng IS NOT NULL AND geohash7 IS NOT NULL
     AND weight_g IS NOT NULL AND volume_cm3 IS NOT NULL AND requires_cold IS NOT NULL AND hazmat IS NOT NULL
     AND promised_start IS NOT NULL AND promised_end IS NOT NULL AND service_seconds IS NOT NULL
     AND promise_revised IS NOT NULL AND priority IS NOT NULL)
    OR
    (status = 'CANCELLED'
     AND wave_id IS NULL AND camp_id IS NULL AND lat IS NULL AND lng IS NULL AND geohash7 IS NULL
     AND weight_g IS NULL AND volume_cm3 IS NULL AND requires_cold IS NULL AND hazmat IS NULL
     AND promised_start IS NULL AND promised_end IS NULL AND service_seconds IS NULL
     AND promise_revised IS NULL AND priority IS NULL AND zone_id IS NULL));

COMMENT ON CONSTRAINT ck_cand_snapshot_or_cancelled_first ON dispatch_candidates IS
  '행의 두 모양 — 스냅샷 전부 · 취소 선착 표식(CANCELLED, 스냅샷 없음). 스냅샷 칸을 더하면 여기에도 더한다 (ADR-074 재검토 지점 1).';
