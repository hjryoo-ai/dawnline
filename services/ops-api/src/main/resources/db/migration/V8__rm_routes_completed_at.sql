-- =============================================================================
-- rm_routes.completed_at · live_count — 라우트의 완료를 쓰기 때 판정한다 (DESIGN.md §5.5, ADR-061)
--
-- 「완료」는 status 의 칸이 아니다(ASSIGNED · DEPARTED 둘뿐). 7-1 에서는 dawnline_routes 의 1분 갱신이 라우트마다
-- rm_orders 를 찾아 판정했고, 그 탐색을 묶는 창이 창 밖의 끝나지 않은 라우트를 뺐다. 끝나지 않은 일에는 창이 없다 —
-- 그래서 판정을 입력이 바뀔 때 그 라우트만 다시 세는 쪽으로 옮긴다. completed_count · failed_count 와 같은 재집계
-- 칸이다(ADR-051 결정 4). 핸들러가 쓰지 않는다.
--
-- live_count: 그 라우트의 비취소 주문 수. 0 이면 그 라우트는 void 다 — 할 일이 없는 라우트(재계획이 주문을 전부
-- 옮겼거나 전부 취소됐다). 완료도 진행 중도 아니다.
--
-- completed_at: 비취소 주문이 있고 그중 결과 없는 것이 남지 않았으면 주문들의 마지막 결과 시각, 아니면 NULL. void 에는
-- 시각을 만들지 않는다 — 일어나지 않은 완료에 시각을 적는 것이다. 전부 사실에서 오므로 처리 순서를 타지 않는다. 계획이
-- 도착한 라우트만(revision IS NOT NULL) — 재집계의 기존 규칙이다.
--
-- 인덱스는 넣지 않는다(불변규칙 11). 읽는 질의(JdbcRouteCounts)는 rm_routes 순차 스캔 한 번이고, 보존 90일 11만 행에서
-- 수 ms 다. 끝나지 않은 라우트의 부분 인덱스는 창 쪽 분기가 순차 스캔이라 값을 하지 않았다
-- (docs/benchmarks/phase7-route-progress-count.md 「둘째 판」). 재검토 지점: rm_routes 가 200만 행에 가까워질 때.
--
-- 아래 UPDATE 가 기존 행을 채운다. 식은 JdbcRouteRows.RECOUNT_SQL 을 옮겨 적은 것이다. 이 파일은 머지 뒤 고치지
-- 않으므로(불변규칙 13) 그 식이 바뀌어도 여기는 따라가지 않는다 — 기존 행은 다음 재집계가 새 식으로 덮는다.
-- =============================================================================

ALTER TABLE rm_routes ADD COLUMN live_count INTEGER;
ALTER TABLE rm_routes ADD COLUMN completed_at TIMESTAMPTZ;

UPDATE rm_routes r
   SET (live_count, completed_at) = (
         SELECT count(*) FILTER (WHERE o.order_status IS DISTINCT FROM 'CANCELLED'),
                CASE WHEN count(*) FILTER (WHERE o.order_status IS DISTINCT FROM 'CANCELLED') = 0
                          OR bool_or(o.delivery_outcome IS NULL AND o.order_status IS DISTINCT FROM 'CANCELLED')
                     THEN NULL
                     ELSE max(COALESCE(o.delivered_at, o.failed_at))
                END
           FROM rm_orders o
          WHERE o.route_id = r.route_id)
 WHERE r.revision IS NOT NULL;

COMMENT ON COLUMN rm_routes.live_count IS
  '집계 — 비취소 주문 수. 0 이면 void(할 일이 없는 라우트). 재집계가 쓴다(ADR-061).';
COMMENT ON COLUMN rm_routes.completed_at IS
  '집계 — 비취소 주문이 있고 결과 없는 것이 남지 않았을 때의 마지막 결과 시각. void 는 NULL. 재집계가 쓴다(ADR-061).';
