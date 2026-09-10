package com.jis.review.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * FSRS 的记忆状态。一个用户对一张卡片最多一行，
 * 用户在**首次评分时**才创建，而不是导入内容时批量预建。
 */
@Data
@TableName("review_state")
public class ReviewState {

    /**
     * 从未复习过。没有 review_state 行的卡片按此状态对待。
     */
    public static final int STATE_NEW = 0;
    public static final int STATE_LEARNING = 1;
    public static final int STATE_REVIEW = 2;
    public static final int STATE_RELEARNING = 3;

    /**
     * 状态的中文说明，用于前端直接展示。
     *
     * @param state 可为 null，表示还没有复习记录
     */
    public static String labelOf(Integer state) {
        if (state == null) {
            return "未复习";
        }

        return switch (state) {
            case STATE_NEW -> "未复习";
            case STATE_LEARNING -> "学习中";
            case STATE_REVIEW -> "复习中";
            case STATE_RELEARNING -> "重新学习";
            default -> "未知状态";
        };
    }

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long kpId;

    private Integer state;

    /**
     * 记忆稳定性 S（天）。越大表示遗忘越慢。
     */
    private Double stability;

    /**
     * 记忆难度 D（1~10）。越大表示这张卡对你越难。
     */
    private Double difficulty;

    private Integer reps;

    private Integer lapses;

    private LocalDateTime dueAt;

    private LocalDateTime lastReviewAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
