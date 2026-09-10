package com.jis.auth.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.jis.auth.entity.User;

public record UserVO(

        @JsonSerialize(using = ToStringSerializer.class)
        Long id,

        String username,

        String nickname,

        String email,

        String role,

        LocalDateTime createTime
) {

    public static UserVO from(User user) {
        return new UserVO(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getRole(),
                user.getCreateTime()
        );
    }
}
