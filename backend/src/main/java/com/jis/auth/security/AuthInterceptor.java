package com.jis.auth.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jis.common.BizException;
import com.jis.common.Result;
import com.jis.common.ResultCode;
import com.jis.common.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器：解析 Authorization 头，校验通过则把 userId 放进 {@link UserContext}。
 *
 * <p>为什么不直接抛异常：拦截器在 HandlerAdapter 之前执行，
 * 抛出的异常不会经过 {@code @RestControllerAdvice}，只能自己写响应体。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final TokenStore tokenStore;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) throws IOException {
        // 预检请求不带 Authorization，放行交给 CORS 处理
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String token = resolveAccessToken(request);
        if (token == null) {
            writeUnauthorized(response, "缺少访问凭证");
            return false;
        }

        JwtClaims claims;
        try {
            claims = tokenProvider.parse(token);
        } catch (BizException e) {
            writeUnauthorized(response, e.getMessage());
            return false;
        }

        if (!JwtTokenProvider.TYPE_ACCESS.equals(claims.tokenType())) {
            writeUnauthorized(response, "凭证类型不正确");
            return false;
        }

        if (tokenStore.isAccessTokenBlacklisted(claims.jti())) {
            writeUnauthorized(response, "凭证已失效，请重新登录");
            return false;
        }

        UserContext.setUserId(claims.userId());

        return true;
    }

    /**
     * 无论请求成功还是抛异常，都必须清理 ThreadLocal，
     * 否则 Tomcat 复用线程时会带着上一个请求的用户身份。
     */
    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex
    ) {
        UserContext.clear();
    }

    private String resolveAccessToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }

        String token = header.substring(BEARER_PREFIX.length())
                .trim();

        return token.isEmpty() ? null : token;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        // HTTP 状态保持 200，业务码走响应体，前端只需在一处判断 code
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        Result<Void> body = Result.failure(ResultCode.UNAUTHORIZED, message);
        response.getWriter()
                .write(objectMapper.writeValueAsString(body));
    }
}
