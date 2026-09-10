package com.jis.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(

        @NotBlank(message = "refreshToken 不能为空")
        String refreshToken
) {
}
