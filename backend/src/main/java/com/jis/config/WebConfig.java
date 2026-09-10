package com.jis.config;

import com.jis.auth.security.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    /**
     * 无需登录即可访问的路径。
     *
     * <p>{@code /api/auth/logout} 也在其中，这不是疏漏：登出只需要请求里带来的
     * token 本身，不需要用户上下文。若走认证拦截器，access token 一旦过期，
     * 登出就会被拒，refresh token 反而在 Redis 里活满整个有效期，
     * 用户以为已登出实际没有。放行后登出始终可用且幂等。
     *
     * <p>放行没有安全风险：伪造的 token 解析就会失败，登出退化为空操作。
     */
    private static final String[] PUBLIC_PATHS = {
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/auth/logout",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(PUBLIC_PATHS);
    }

    /**
     * 前端开发服务器通过 Vite 代理访问，同源时本配置不生效；
     * 保留它是为了能用 Swagger 页面之外的直连调试。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
