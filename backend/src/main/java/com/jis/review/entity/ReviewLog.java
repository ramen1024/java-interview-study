package com.jis.review.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 复习日志，只追加不修改，用于统计与后续调参。
 */
@Data
@TableName("review_log")
public class ReviewLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long kpId;

    /**
     * 1 不会 / 2 模糊 / 3 会讲 / 4 轻松。
     */
    private Integer rating;

    private Integer durationMs;

    private Double stabilityAfter;

    private Double difficultyAfter;

    private Double intervalDays;

    private LocalDateTime reviewedAt;
}
