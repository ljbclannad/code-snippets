-- 库存回补 Lua 脚本
-- KEYS[1]: appointment:stock:{scheduleId}
-- KEYS[2]: appointment:buyers:{scheduleId}
-- ARGV[1]: userId

-- 1. 检查用户是否存在（幂等性保证）
local exists = redis.call('SISMEMBER', KEYS[2], ARGV[1])
if exists == 0 then
    return 'NOT_FOUND'
end

-- 2. 增加库存
redis.call('INCR', KEYS[1])

-- 3. 移除用户记录
redis.call('SREM', KEYS[2], ARGV[1])

return 'REFUNDED'
