package com.jis.content.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 知识点卡片。六段式结构的字段对应关系见 docs/content-spec.md。
 *
 * <p>正文一律以 Markdown 原文存储，渲染交给前端：
 * 后端只负责检索、关联与复习状态，不承担排版职责。
 */
@Data
@TableName("knowledge_point")
public class KnowledgePoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long moduleId;

    private String slug;

    private String title;

    /**
     * 第一段：电梯版回答，30 秒能讲完，面试开场直接用。
     */
    private String elevatorAnswer;

    /**
     * 第二段：展开讲解。
     */
    private String detailMd;

    /**
     * 第四段：常见坑。
     */
    private String pitfallsMd;

    /**
     * 第四段：加分点。
     */
    private String bonusMd;

    /**
     * 第五段：版本差异。
     */
    private String versionDiffMd;

    /**
     * 1 易 / 2 中 / 3 难。
     */
    private Integer difficulty;

    /**
     * 面试热度 1 低频 / 2 常见 / 3 高频。
     */
    private Integer frequency;

    /**
     * 逗号分隔。同时参与全文索引，所以放在 knowledge_point 表内而非关联表。
     */
    private String tags;

    private String sourcePath;

    private Integer sort;

    private Integer viewCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
