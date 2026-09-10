package com.jis.auth.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.jis.common.BizException;
import com.jis.common.ResultCode;
import com.jis.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * JWT 签发与校验。
 *
 * <p>两种 token 用同一密钥签名，靠 {@code typ} 声明区分用途：
 * access 用于日常请求（无状态，不查库）；refresh 只用于换发新 token（其 jti 存 Redis，可吊销）。
 */
@Slf4j
@Component
public class JwtTokenProvider {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_TOKEN_TYPE = "typ";

    private final SecretKey secretKey;
    private final JwtProperties properties;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        this.secretKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public IssuedToken createAccessToken(Long userId, String username) {
        return buildToken(userId, username, TYPE_ACCESS, properties.accessTokenTtl());
    }

    public IssuedToken createRefreshToken(Long userId, String username) {
        return buildToken(userId, username, TYPE_REFRESH, properties.refreshTokenTtl());
    }

    public Duration accessTokenTtl() {
        return properties.accessTokenTtl();
    }

    /**
     * 解析并校验签名与有效期。任何失败都统一转成 401，不向调用方泄漏具体原因。
     */
    public JwtClaims parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return new JwtClaims(
                    Long.valueOf(claims.getSubject()),
                    claims.get(CLAIM_USERNAME, String.class),
                    claims.get(CLAIM_TOKEN_TYPE, String.class),
                    claims.getId(),
                    claims.getExpiration()
                            .toInstant()
            );
        } catch (ExpiredJwtException e) {
            throw new BizException(ResultCode.TOKEN_INVALID, "凭证已过期，请重新登录");
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 校验失败: {}", e.getMessage());

            throw new BizException(ResultCode.TOKEN_INVALID);
        }
    }

    private IssuedToken buildToken(Long userId, String username, String tokenType, Duration ttl) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(ttl);
        String jti = UUID.randomUUID()
                .toString();

        String token = Jwts.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_TOKEN_TYPE, tokenType)
                .id(jti)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();

        return new IssuedToken(token, jti, expiresAt);
    }
}
