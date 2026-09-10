package com.jis.common;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 的互斥锁。
 *
 * <p>两个容易写错的地方在这里被一次性处理掉：
 *
 * <ul>
 *   <li><b>必须设置过期时间</b>。持锁进程崩了不会永久死锁，代价是临界区
 *       必须短于 TTL，否则会出现两个持有者。</li>
 *   <li><b>解锁必须校验持有者</b>。直接 {@code DEL} 会误删别人的锁：
 *       A 的业务超时后锁自动过期，B 拿到锁，此时 A 执行完来删锁，
 *       删掉的就是 B 的锁。用 token 比对 + Lua 保证"比较并删除"是原子的。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RedisLock {

    /**
     * 比较 token 相等才删除，Lua 保证这两步在 Redis 侧原子执行。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * @return 持锁凭证；未抢到锁返回 null
     */
    public String tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID()
                .toString();

        boolean acquired = Boolean.TRUE.equals(
                stringRedisTemplate.opsForValue()
                        .setIfAbsent(key, token, ttl)
        );

        return acquired ? token : null;
    }

    /**
     * 释放锁。token 不匹配（说明锁已过期并被他人持有）时什么都不做。
     */
    public void unlock(String key, String token) {
        if (token == null) {
            return;
        }

        stringRedisTemplate.execute(UNLOCK_SCRIPT, List.of(key), token);
    }
}
