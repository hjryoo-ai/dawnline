-- 릴레이 리더십 획득 또는 갱신 (DESIGN.md §4.4, §7.2 lock:relay:{service}, ADR-027).
--
-- 왜 Lua 인가: 획득과 갱신이 **한 번의 왕복**이어야 한다. GET 으로 확인한 뒤 PEXPIRE 하면 그
-- 사이에 TTL 이 만료되고 다른 인스턴스가 키를 잡을 수 있고, 그러면 이쪽의 PEXPIRE 가 **남의
-- 리더십 시계를 늘린다.** 100ms 마다 도는 호출이라 그 창은 이론이 아니다.
--
-- KEYS[1] 리더 키
-- ARGV[1] 이 인스턴스의 토큰
-- ARGV[2] TTL(ms)
-- 반환: 리더면 1, 아니면 0

if redis.call('GET', KEYS[1]) == ARGV[1] then
  redis.call('PEXPIRE', KEYS[1], ARGV[2])
  return 1
end
if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
  return 1
end
return 0
