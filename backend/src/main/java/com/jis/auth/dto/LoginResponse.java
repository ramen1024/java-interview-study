package com.jis.auth.dto;

public record LoginResponse(

        String accessToken,

        String refreshToken,

        String tokenType,

        /**
         * access token 剩余有效期（秒），前端据此安排静默续期时机。
         */
        long expiresIn,

        UserVO user
) {
}
