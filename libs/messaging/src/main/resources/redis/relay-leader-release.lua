-- 릴레이 리더십 해제 (DESIGN.md §4.4, ADR-027).
--
-- 왜 Lua 인가: DEL 만 하면 **남의 리더십을 지울 수 있다**. 이 인스턴스가 멈춰 있는 동안 TTL 이
-- 만료되고 다른 인스턴스가 리더가 됐다면, 뒤늦게 도착한 이쪽의 DEL 이 그 리더를 끌어내린다.
-- 비교와 삭제가 원자적이어야 한다.
--
-- KEYS[1] 리더 키
-- ARGV[1] 이 인스턴스의 토큰
-- 반환: 실제로 지웠으면 1, 남의 것이라 두었으면 0

if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
