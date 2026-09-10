package com.jis.auth.service;

import java.time.Duration;
import java.time.Instant;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jis.auth.dto.LoginRequest;
import com.jis.auth.dto.LoginResponse;
import com.jis.auth.dto.RegisterRequest;
import com.jis.auth.dto.UserVO;
import com.jis.auth.entity.User;
import com.jis.auth.mapper.UserMapper;
import com.jis.auth.security.IssuedToken;
import com.jis.auth.security.JwtClaims;
import com.jis.auth.security.JwtTokenProvider;
import com.jis.auth.security.TokenStore;
import com.jis.common.BizException;
import com.jis.common.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String TOKEN_TYPE_BEARER = "Bearer";
    private static final int STATUS_ENABLED = 1;
    private static final String DEFAULT_ROLE = "USER";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final TokenStore tokenStore;

    @Transactional
    public UserVO register(RegisterRequest request) {
        boolean usernameTaken = userMapper.exists(
                Wrappers.<User>lambdaQuery()
                        .eq(User::getUsername, request.username())
        );
        if (usernameTaken) {
            throw new BizException(ResultCode.USERNAME_EXISTS);
        }

        User user = new User();
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(resolveNickname(request));
        user.setEmail("");
        user.setRole(DEFAULT_ROLE);
        user.setStatus(STATUS_ENABLED);
        userMapper.insert(user);

        log.info("新用户注册成功 userId={} username={}", user.getId(), user.getUsername());

        return UserVO.from(userMapper.selectById(user.getId()));
    }

    public LoginResponse login(LoginRequest request) {
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery()
                        .eq(User::getUsername, request.username())
        );

        // 刻意不区分"用户不存在"与"密码错误"，避免攻击者拿响应差异枚举用户名
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BizException(ResultCode.PASSWORD_INCORRECT);
        }

        if (!Integer.valueOf(STATUS_ENABLED)
                .equals(user.getStatus())) {
            throw new BizException(ResultCode.USER_DISABLED);
        }

        return issueTokens(user);
    }

    /**
     * 用 refresh token 换发新的一对 token，并轮换 refresh token。
     * 轮换的意义：旧 refresh token 立刻失效，即使泄漏也只能用一次。
     */
    @Transactional
    public LoginResponse refresh(String refreshToken) {
        JwtClaims claims = tokenProvider.parse(refreshToken);
        if (!JwtTokenProvider.TYPE_REFRESH.equals(claims.tokenType())) {
            throw new BizException(ResultCode.TOKEN_INVALID, "凭证类型不正确");
        }

        Long ownerId = tokenStore.findRefreshTokenOwner(claims.jti());
        if (ownerId == null || !ownerId.equals(claims.userId())) {
            throw new BizException(ResultCode.TOKEN_INVALID, "凭证已失效，请重新登录");
        }

        tokenStore.removeRefreshToken(claims.jti());

        User user = userMapper.selectById(claims.userId());
        if (user == null || !Integer.valueOf(STATUS_ENABLED)
                .equals(user.getStatus())) {
            throw new BizException(ResultCode.TOKEN_INVALID, "账号不可用");
        }

        return issueTokens(user);
    }

    /**
     * 登出：access token 进黑名单（TTL 等于剩余有效期），refresh token 直接从 Redis 删除。
     * 两个动作都是幂等的，token 无效或已过期时静默跳过，因此重复登出始终成功
     * （该接口在 WebConfig 中放行，不受认证拦截器约束）。
     */
    public void logout(String authorizationHeader, String refreshToken) {
        if (authorizationHeader != null && authorizationHeader.startsWith(BEARER_PREFIX)) {
            String accessToken = authorizationHeader.substring(BEARER_PREFIX.length());
            revokeAccessToken(accessToken);
        }

        if (refreshToken != null && !refreshToken.isBlank()) {
            revokeRefreshToken(refreshToken);
        }
    }

    public UserVO getCurrentUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.USER_NOT_FOUND);
        }

        return UserVO.from(user);
    }

    private void revokeAccessToken(String accessToken) {
        try {
            JwtClaims claims = tokenProvider.parse(accessToken);
            Duration remaining = TokenStore.remainingTtl(claims.expiresAt());
            tokenStore.blacklistAccessToken(claims.jti(), remaining);
        } catch (BizException e) {
            // 已过期或签名非法的 token 本来就用不了，无需入黑名单
            log.debug("登出时 access token 已不可用，跳过黑名单");
        }
    }

    private void revokeRefreshToken(String refreshToken) {
        try {
            JwtClaims claims = tokenProvider.parse(refreshToken);
            tokenStore.removeRefreshToken(claims.jti());
        } catch (BizException e) {
            log.debug("登出时 refresh token 已不可用，跳过删除");
        }
    }

    private LoginResponse issueTokens(User user) {
        IssuedToken access = tokenProvider.createAccessToken(user.getId(), user.getUsername());
        IssuedToken refresh = tokenProvider.createRefreshToken(user.getId(), user.getUsername());

        Duration refreshTtl = Duration.between(Instant.now(), refresh.expiresAt());
        tokenStore.saveRefreshToken(refresh.jti(), user.getId(), refreshTtl);

        return new LoginResponse(
                access.token(),
                refresh.token(),
                TOKEN_TYPE_BEARER,
                tokenProvider.accessTokenTtl()
                        .toSeconds(),
                UserVO.from(user)
        );
    }

    private String resolveNickname(RegisterRequest request) {
        if (request.nickname() == null || request.nickname()
                .isBlank()) {
            return request.username();
        }

        return request.nickname()
                .trim();
    }
}
