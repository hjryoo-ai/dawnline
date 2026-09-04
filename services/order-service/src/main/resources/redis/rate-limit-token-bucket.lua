-- 고객별 토큰 버킷 (DESIGN.md §7.2 rl:customer:{id}).
--
-- 용량 60, 초당 1개 리필이 기본이다. 정확히 "분당 60회" 가 아니라 분당 60을 넘는 지속 부하를
-- 막되 짧은 버스트는 허용한다는 뜻이다(§7.2).
--
-- 왜 Lua 인가: 읽기 → 리필 계산 → 쓰기가 원자적이어야 한다. WATCH/MULTI 로 하면 경합 시
-- 재시도가 필요하고, 그 재시도가 핫패스에 붙는다.
--
-- 왜 시각을 인자로 받는가: 불변규칙 12(시간은 주입). redis.call('TIME') 을 쓰면 스크립트가
-- 비결정적이 되고, 무엇보다 리필을 테스트로 재현할 수 없다. 인스턴스 간 시계 오차는 NTP 로
-- 밀리초 수준이고 이 버킷의 분해능(초)보다 훨씬 작다.
--
-- KEYS[1] 버킷 키
-- ARGV[1] 용량      ARGV[2] 초당 리필      ARGV[3] 현재 시각(epoch millis)      ARGV[4] TTL(초)
-- 반환: {허용 여부(1/0), 다음 토큰까지 남은 초(올림)}

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillPerSecond = tonumber(ARGV[2])
local nowMillis = tonumber(ARGV[3])
local ttlSeconds = tonumber(ARGV[4])

local bucket = redis.call('HMGET', key, 'tokens', 'updatedAt')
local tokens = tonumber(bucket[1])
local updatedAt = tonumber(bucket[2])

if tokens == nil or updatedAt == nil then
  -- 처음 보는 고객(또는 TTL 만료). 가득 찬 버킷으로 시작한다 — 오래 쉬었으면 어차피 다 찼을 값이다.
  tokens = capacity
  updatedAt = nowMillis
end

-- 시계가 뒤로 간 경우(NTP 보정) elapsed 를 0 으로 눌러 토큰이 줄지 않게 한다.
local elapsedMillis = nowMillis - updatedAt
if elapsedMillis < 0 then
  elapsedMillis = 0
end

tokens = math.min(capacity, tokens + elapsedMillis * refillPerSecond / 1000.0)

local allowed = 0
local retryAfterSeconds = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retryAfterSeconds = math.ceil((1 - tokens) / refillPerSecond)
  if retryAfterSeconds < 1 then
    retryAfterSeconds = 1
  end
end

redis.call('HSET', key, 'tokens', tokens, 'updatedAt', nowMillis)
redis.call('EXPIRE', key, ttlSeconds)

return {allowed, retryAfterSeconds}
