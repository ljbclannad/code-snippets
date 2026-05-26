-- 库存回补 Lua 脚本
-- KEYS[1]: appointment:stock:{scheduleId}
-- KEYS[2]: appointment:buyers:{scheduleId}
-- ARGV[1]: userId

-- 1. 增加库存
redis.call('INCR', KEYS[1])

-- 2. 移除用户记录
redis.call('SREM', KEYS[2], ARGV[1])

return 1
