package com.jis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 只取 spring-security-crypto 的密码编码器，不引入完整 Spring Security 过滤器链：
 * 认证由 {@code AuthInterceptor} 承担，链路更短、更好讲清楚。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
