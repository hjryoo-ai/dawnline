-- 차량의 출처 (DESIGN.md §5.3 · 2026-09-27, ADR-067 결정 4).
--
-- 누가 넣었나를 칸이 말한다 — 코드 접두어(PS-…)로 말하면 이름에 뜻을 싣는 것이다. 값 집합은 닫혔다:
--   seed      — 마이그레이션(R__seed_dispatch)이 넣은 운영 시드
--   operator  — 운영자가 POST /vehicles 로 넣은 차량 (기본)
--   peak-sim  — 성수기 증차를 흉내 낸 시뮬레이터(peak-day)가 넣은 차량. 실행이 끝나면 그 실행의 것만 비활성화한다
-- 기본값이 operator 인 이유: 이 칸을 모르는 INSERT 는 운영자의 것이다 — 시드는 아래 UPDATE 와 R__ 가 스스로 적는다.
ALTER TABLE vehicles
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'operator'
      CHECK (source IN ('seed', 'operator', 'peak-sim'));

-- 시드 차량 200대. R__seed_dispatch 는 체크섬이 바뀌어 이 뒤에 다시 돌고 같은 값을 적는다 — 이 UPDATE 는
-- R__ 가 source 를 아직 모르는 판(이미 적용된 체크섬)으로 멈춘 DB 에서도 시드 행이 operator 로 남지 않게 한다.
UPDATE vehicles SET source = 'seed'
 WHERE id BETWEEN '01a06edd-6c00-7000-8004-000000000001' AND '01a06edd-6c00-7000-8004-000000000200';
