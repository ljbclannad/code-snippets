package io.github.messageguard.store.redis;

public class RedisLockSupport {

    public boolean tryLock(String key) {
        return key != null && !key.isBlank();
    }
}
