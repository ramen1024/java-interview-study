package com.jis.content.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 面试追问链的一个节点。
 *
 * <p>树形结构由 {@code qKey} 的编号自然表达：Q1 → Q1.1 → Q1.1.1，
 * 层级即 {@code depth}，父节点即去掉最后一段的编号。这样 Markdown 作者
 * 不可能写出结构错乱的追问链。
 */
@Data
@TableName("follow_up")
public class FollowUp {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kpId;

    /**
     * 0 表示追问链第一层。
     */
    private Long parentId;

    /**
     * {@code kpId + qKey} 唯一，是增量导入时对齐节点的键。
     */
    private String qKey;

    private String question;

    private String answerMd;

    private Integer depth;

    private Integer sort;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
