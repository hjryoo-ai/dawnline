-- =============================================================================
-- route_stops 의 순번 UNIQUE 를 지연 제약으로 바꾼다 (DESIGN.md §5.3, §6.8).
--
-- V1 은 (route_id, seq) 에 즉시 검사 UNIQUE 를, 같은 컬럼에 CHECK (seq >= 1) 을 걸었다.
-- 이 조합이 순번 재부여를 불가능하게 만든다: 1..n 을 다른 순서의 1..n 으로 다시 쓰려면
-- 중간 상태에서 반드시 두 행이 같은 순번을 갖는 순간이 있는데, 즉시 검사가 그 순간에 터진다.
--
-- 어댑터는 이것을 "잠시 다른 자리로 피신시켰다가 돌아온다" 로 우회했다. 처음에는 `seq = -seq`
-- 였고(CHECK 위반 — 한 번도 성공한 적이 없다), 고친 뒤에는 `seq = seq + 1000` 이었다.
-- 1000 이 안전한 근거는 "max-stops 상한이 120" 인데, 그 값은 dispatch_rules 의 파라미터라
-- 운영자가 PUT /rules 로 코드 변경 없이 올릴 수 있다. **룰을 데이터로 만든 이유가 곧 그 상수를
-- 무효로 만드는 경로다.** 상수를 키우는 것은 같은 결함을 뒤로 미루는 일이다.
--
-- 피신 자체가 트릭이고, 트릭이 필요 없는 스키마가 옳은 스키마다. 제약을 트랜잭션 끝까지
-- 미루면 중간 상태는 검사 대상이 아니고, 커밋 시점의 최종 상태만 유일하면 된다 — 그것이
-- 실제로 지켜야 하는 불변식이다.
--
-- 지연 제약의 대가는 위반이 COMMIT 에서 터진다는 것이다(어느 UPDATE 가 원인인지 스택이
-- 가리키지 않는다). 받아들이는 이유: 이 테이블에 순번을 쓰는 곳은 JdbcRouteMutations 하나이고,
-- 그 안에서 최종 상태가 1..n 임을 도메인이 보장한다.
--
-- ON CONFLICT 는 지연 제약의 인덱스를 중재자로 쓸 수 없다. route_stops 에는 ON CONFLICT 를
-- 쓰는 INSERT 가 없으므로(멱등은 route_plans.wave_id 와 dispatch_candidates.order_id 가 잡는다)
-- 이 변경이 막는 경로가 없다.
--
-- V1·V2 는 이미 main 에 있으므로 고치지 않는다 (불변규칙 13, 예외 없음).
-- =============================================================================

-- 제약 이름은 PostgreSQL 이 테이블-컬럼-key 로 자동 생성한 것이다. DEFERRABLE 로 바꾸는
-- ALTER 는 FK 에만 있으므로 지우고 다시 만든다. 같은 이름을 유지해 EXPLAIN·오류 메시지가
-- V1 때와 같은 것을 가리키게 한다.
ALTER TABLE route_stops DROP CONSTRAINT route_stops_route_id_seq_key;

ALTER TABLE route_stops
  ADD CONSTRAINT route_stops_route_id_seq_key UNIQUE (route_id, seq)
  DEFERRABLE INITIALLY DEFERRED;

COMMENT ON CONSTRAINT route_stops_route_id_seq_key ON route_stops IS
  '지연 제약이다. 순번 재부여가 중간 상태에서 반드시 충돌하기 때문이고(§6.8), 지켜야 하는 불변식은 커밋 시점의 유일성이다.';
