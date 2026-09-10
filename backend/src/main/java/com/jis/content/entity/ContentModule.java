package com.jis.content.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("content_module")
public class ContentModule {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 0 表示一级模块。
     */
    private Long parentId;

    private String name;

    /**
     * 稳定业务键，导入时以它为准做 upsert（不依赖自增 id，重导不会漂移）。
     */
    private String slug;

    private String description;

    private String icon;

    private Integer sort;

    /**
     * 1 一级 / 2 二级。
     */
    private Integer level;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
