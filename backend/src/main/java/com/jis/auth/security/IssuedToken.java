package com.jis.auth.security;

import java.time.Instant;

/**
 * 刚签发的 token 及其元信息。
 *
 * <p>refresh token 需要把 jti 写进 Redis，若签发后再解析一次纯属浪费，
 * 所以这里一并返回。
 */
public record IssuedToken(

        String token,

        String jti,

        Instant expiresAt
) {
}
