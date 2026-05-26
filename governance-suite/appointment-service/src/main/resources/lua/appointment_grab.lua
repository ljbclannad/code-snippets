-- 抢号 Lua 脚本
-- KEYS[1]: appointment:stock:{scheduleId}
-- KEYS[2]: appointment:buyers:{scheduleId}
-- KEYS[3]: appointment:stream:orders
-- ARGV[1]: userId
-- ARGV[2]: scheduleId
-- ARGV[3]: requestId
-- ARGV[4]: timestamp

-- 1. 检查库存
local stock = redis.call('GET', KEYS[1])
if not stock or tonumber(stock) <= 0 then
    return 'SOLD_OUT'
end

-- 2. 检查用户是否已抢过
local exists = redis.call('SISMEMBER', KEYS[2], ARGV[1])
if exists == 1 then
    return 'ALREADY_GRABBED'
end

-- 3. 扣减库存
redis.call('DECR', KEYS[1])

-- 4. 记录用户
redis.call('SADD', KEYS[2], ARGV[1])

-- 5. 写入 Stream
redis.call('XADD', KEYS[3], '*',
    'userId', ARGV[1],
    'scheduleId', ARGV[2],
    'requestId', ARGV[3],
    'timestamp', ARGV[4])

return 'SUCCESS'
