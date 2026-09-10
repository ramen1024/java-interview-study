package com.jis.content.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("user_note")
public class UserNote {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long kpId;

    private String contentMd;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
