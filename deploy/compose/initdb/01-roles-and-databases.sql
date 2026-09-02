-- =============================================================================
-- Dawnline — 서비스별 역할·데이터베이스 생성 (DESIGN.md §7.1 DB-per-service)
--
-- PostgreSQL 공식 이미지의 엔트리포인트가 첫 기동 시 슈퍼유저 세션으로 실행한다
-- (psql -v ON_ERROR_STOP=1). 파일명 알파벳 순서대로 실행되므로 01 → 02 순이다.
--
-- 목표: 한 인스턴스 안에서 서비스마다 DB 와 계정을 분리하고,
--       CONNECT 권한을 소유자에게만 남겨 **교차 접근을 물리적으로 차단**한다.
--       (CLAUDE.md 불변 규칙 3 — 서비스 간 DB 접근 금지)
--
-- 비밀번호는 compose 가 컨테이너에 주입한 환경 변수에서 읽는다(psql \getenv, PG14+).
-- 환경 변수가 없으면 치환이 실패하고 ON_ERROR_STOP 으로 기동이 중단된다(조용한 실패 없음).
-- =============================================================================

\getenv order_pw        DAWNLINE_ORDER_DB_PASSWORD
\getenv fulfillment_pw  DAWNLINE_FULFILLMENT_DB_PASSWORD
\getenv dispatch_pw     DAWNLINE_DISPATCH_DB_PASSWORD
\getenv tracking_pw     DAWNLINE_TRACKING_DB_PASSWORD
\getenv ops_pw          DAWNLINE_OPS_DB_PASSWORD

-- --- 역할 -------------------------------------------------------------------
CREATE ROLE dawnline_order       WITH LOGIN PASSWORD :'order_pw';
CREATE ROLE dawnline_fulfillment WITH LOGIN PASSWORD :'fulfillment_pw';
CREATE ROLE dawnline_dispatch    WITH LOGIN PASSWORD :'dispatch_pw';
CREATE ROLE dawnline_tracking    WITH LOGIN PASSWORD :'tracking_pw';
CREATE ROLE dawnline_ops         WITH LOGIN PASSWORD :'ops_pw';

-- --- 데이터베이스 (소유자 = 동명 서비스 계정) --------------------------------
CREATE DATABASE dawnline_order       OWNER dawnline_order;
CREATE DATABASE dawnline_fulfillment OWNER dawnline_fulfillment;
CREATE DATABASE dawnline_dispatch    OWNER dawnline_dispatch;
CREATE DATABASE dawnline_tracking    OWNER dawnline_tracking;
CREATE DATABASE dawnline_ops         OWNER dawnline_ops;

-- --- 교차 접근 차단 ---------------------------------------------------------
-- PUBLIC 의 기본 CONNECT 를 회수하면 소유자(와 슈퍼유저)만 접속할 수 있다.
-- 예: dawnline_dispatch 계정으로 dawnline_order DB 에 접속하면 즉시 거부된다.
REVOKE CONNECT ON DATABASE dawnline_order       FROM PUBLIC;
REVOKE CONNECT ON DATABASE dawnline_fulfillment FROM PUBLIC;
REVOKE CONNECT ON DATABASE dawnline_dispatch    FROM PUBLIC;
REVOKE CONNECT ON DATABASE dawnline_tracking    FROM PUBLIC;
REVOKE CONNECT ON DATABASE dawnline_ops         FROM PUBLIC;

-- 부트스트랩 DB 도 막는다. 위 다섯 줄만으로는 서비스 계정이 dawnline_admin·postgres·template1 에
-- 붙을 수 있다(PostgreSQL 은 CONNECT 를 PUBLIC 에 기본 부여한다). 거기서 남의 데이터를 읽지는
-- 못하지만 — public 스키마에 CREATE 권한이 없고, pg_authid 도 못 읽고, dblink/postgres_fdw 도
-- 설치할 수 없다 — 시스템 카탈로그는 보인다. "교차 접근을 물리적으로 차단" 이라고 적어 둔 이상
-- 예외를 남기지 않는다.
--
-- 부트스트랩 DB 의 이름은 `.env` 의 POSTGRES_DB 가 정한다. 그 이름을 여기에 다시 적으면
-- 두 곳이 어긋나는 순간 `REVOKE ... ON DATABASE <없는 이름>` 이 되고, 엔트리포인트가
-- ON_ERROR_STOP 으로 도는 탓에 **컨테이너가 아예 기동하지 못한다**. 이 스크립트는 첫 기동에만
-- 실행되므로 증상은 "볼륨을 지우기 전까지 되던 것이 안 됨" 으로 나타난다. 이름을 받아쓰지 않고
-- 지금 붙어 있는 DB 를 그대로 쓴다.
DO $$
BEGIN
    EXECUTE format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC', current_database());
END
$$;

-- postgres·template1 은 initdb 가 항상 만드는 고정 이름이라 그대로 적는다.
REVOKE CONNECT ON DATABASE postgres  FROM PUBLIC;
REVOKE CONNECT ON DATABASE template1 FROM PUBLIC;

-- --- 시간대: TIMESTAMPTZ / Instant 일관성 (CLAUDE.md 불변 규칙 9) -----------
ALTER DATABASE dawnline_order       SET timezone TO 'UTC';
ALTER DATABASE dawnline_fulfillment SET timezone TO 'UTC';
ALTER DATABASE dawnline_dispatch    SET timezone TO 'UTC';
ALTER DATABASE dawnline_tracking    SET timezone TO 'UTC';
ALTER DATABASE dawnline_ops         SET timezone TO 'UTC';
