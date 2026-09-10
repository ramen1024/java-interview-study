package com.jis.auth.controller;

import com.jis.auth.dto.LoginRequest;
import com.jis.auth.dto.LoginResponse;
import com.jis.auth.dto.RefreshTokenRequest;
import com.jis.auth.dto.RegisterRequest;
import com.jis.auth.dto.UserVO;
import com.jis.auth.service.AuthService;
import com.jis.common.Result;
import com.jis.common.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证", description = "注册、登录、刷新凭证、登出")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "注册")
    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(authService.register(request));
    }

    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @Operation(summary = "刷新凭证", description = "用 refresh token 换取新的一对 token，旧 refresh token 立即失效")
    @PostMapping("/refresh")
    public Result<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return Result.success(authService.refresh(request.refreshToken()));
    }

    @Operation(summary = "登出", description = "access token 入黑名单，refresh token 从 Redis 删除")
    @PostMapping("/logout")
    public Result<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) RefreshTokenRequest request
    ) {
        String refreshToken = request == null ? null : request.refreshToken();
        authService.logout(authorization, refreshToken);

        return Result.success();
    }

    @Operation(summary = "当前登录用户")
    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.success(authService.getCurrentUser(UserContext.requireUserId()));
    }
}
