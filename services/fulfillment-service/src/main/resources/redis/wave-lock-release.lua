-- 웨이브 마감 락 해제 (DESIGN.md §5.2, §7.2 lock:wave:{id}).
--
-- 왜 Lua 인가: DEL 만 하면 **남의 락을 지울 수 있다**. 이 인스턴스가 멈춰 있는 동안 TTL 이
-- 만료되고 다른 인스턴스가 같은 키로 락을 잡았다면, 뒤늦게 깨어난 이쪽의 DEL 이 그 락을 푼다.
-- 값을 비교한 뒤 지워야 하고, 비교와 삭제가 원자적이어야 한다.
--
-- KEYS[1] 락 키
-- ARGV[1] 이 인스턴스가 쓴 토큰
-- 반환: 실제로 지웠으면 1, 남의 것이라 두었으면 0

if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
