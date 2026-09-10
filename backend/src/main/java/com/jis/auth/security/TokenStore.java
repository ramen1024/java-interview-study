package com.jis.auth.security;

import java.time.Duration;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 用 Redis 补齐 JWT 的两个短板：无法吊销、无法记录登录态。
 *
 * <p>- access token 登出后写入黑名单，key 的 TTL 等于其剩余有效期，
 *   到期自动清理，黑名单不会无限增长。<br>
 * - refresh token 的 jti 存在 Redis 里，登出即删除，实现真正的吊销；
 *   每次刷新都轮换（删旧发新），旧的 refresh token 立刻失效。
 */
@Component
@RequiredArgsConstructor
public class TokenStore {

    private static final String REFRESH_KEY_PREFIX = "jis:auth:refresh:";
    private static final String BLACKLIST_KEY_PREFIX = "jis:auth:blacklist:";

    private final StringRedisTemplate stringRedisTemplate;

    public void saveRefreshToken(String jti, Long userId, Duration ttl) {
        stringRedisTemplate.opsForValue()
                .set(refreshKey(jti), String.valueOf(userId), ttl);
    }

    /**
     * @return 该 refresh token 对应的用户 ID；不存在（已登出或已轮换）时返回 null
     */
    public Long findRefreshTokenOwner(String jti) {
        String value = stringRedisTemplate.opsForValue()
                .get(refreshKey(jti));
        if (value == null) {
            return null;
        }

        return Long.valueOf(value);
    }

    public void removeRefreshToken(String jti) {
        stringRedisTemplate.delete(refreshKey(jti));
    }

    /**
     * @param remainingTtl 剩余有效期，<= 0 表示已过期，无需入黑名单
     */
    public void blacklistAccessToken(String jti, Duration remainingTtl) {
        if (remainingTtl.isNegative() || remainingTtl.isZero()) {
            return;
        }

        stringRedisTemplate.opsForValue()
                .set(blacklistKey(jti), "1", remainingTtl);
    }

    public boolean isAccessTokenBlacklisted(String jti) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(blacklistKey(jti)));
    }

    public static Duration remainingTtl(Instant expiresAt) {
        return Duration.between(Instant.now(), expiresAt);
    }

    private String refreshKey(String jti) {
        return REFRESH_KEY_PREFIX + jti;
    }

    private String blacklistKey(String jti) {
        return BLACKLIST_KEY_PREFIX + jti;
    }
}
