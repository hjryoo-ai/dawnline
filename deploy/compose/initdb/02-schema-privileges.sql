-- =============================================================================
-- Dawnline — 서비스 DB 의 public 스키마 소유권 이관
--
-- PostgreSQL 15 부터 public 스키마의 CREATE 권한이 PUBLIC 에서 회수됐다.
-- Flyway 가 자기 DB 에 마이그레이션을 적용하려면 서비스 계정이 스키마 소유자여야 한다.
-- =============================================================================

\connect dawnline_order
ALTER SCHEMA public OWNER TO dawnline_order;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT  ALL ON SCHEMA public TO dawnline_order;

\connect dawnline_fulfillment
ALTER SCHEMA public OWNER TO dawnline_fulfillment;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT  ALL ON SCHEMA public TO dawnline_fulfillment;

\connect dawnline_dispatch
ALTER SCHEMA public OWNER TO dawnline_dispatch;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT  ALL ON SCHEMA public TO dawnline_dispatch;

\connect dawnline_tracking
ALTER SCHEMA public OWNER TO dawnline_tracking;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT  ALL ON SCHEMA public TO dawnline_tracking;

\connect dawnline_ops
ALTER SCHEMA public OWNER TO dawnline_ops;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT  ALL ON SCHEMA public TO dawnline_ops;
