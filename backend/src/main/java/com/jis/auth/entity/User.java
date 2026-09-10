package com.jis.auth.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
@TableName("sys_user")
public class User {

    /**
     * 序列化为字符串。BIGINT 超出 JS 的 Number.MAX_SAFE_INTEGER，
     * 直接给数字会在前端丢精度。
     */
    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String username;

    /**
     * BCrypt 散列，绝不外泄：所有对外接口只返回 {@code UserVO}。
     */
    private String password;

    private String nickname;

    private String email;

    private String role;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
