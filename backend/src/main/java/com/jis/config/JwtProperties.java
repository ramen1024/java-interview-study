package com.jis.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 相关配置，对应 application.yml 中的 {@code jis.jwt.*}。
 *
 * <p>access token 短命且无状态，用于日常请求；
 * refresh token 长命且其 jti 落 Redis，因此可以真正吊销（登出、改密码、风控）。
 */
@ConfigurationProperties(prefix = "jis.jwt")
public record JwtProperties(
        String secret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        String issuer
) {

    public JwtProperties {
        if (secret == null || secret.getBytes().length < 32) {
            throw new IllegalStateException("jis.jwt.secret 长度不足 32 字节，HS256 无法安全签名");
        }
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofHours(2);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(7);
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "jis";
        }
    }
}
