package com.jis.auth.security;

import java.time.Instant;

public record JwtClaims(

        Long userId,

        String username,

        /**
         * {@link JwtTokenProvider#TYPE_ACCESS} 或 {@link JwtTokenProvider#TYPE_REFRESH}。
         * 拦截器据此拒绝把 refresh token 当访问凭证使用。
         */
        String tokenType,

        /**
         * JWT 唯一标识。access token 登出后进 Redis 黑名单，
         * refresh token 以它为 Redis key 实现可吊销。
         */
        String jti,

        Instant expiresAt
) {
}
