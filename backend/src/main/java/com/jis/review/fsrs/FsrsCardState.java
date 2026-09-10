package com.jis.review.fsrs;

import com.jis.review.entity.ReviewState;

/**
 * 调度器的输入：一张卡片当前的记忆状态。
 *
 * <p>{@code state} 用 {@link ReviewState} 上的常量，默认全为 0 即 NEW，
 * 所以「从未复习过」的卡片可以直接用 {@link #newCard()} 表示。
 */
public record FsrsCardState(

        int state,

        double stability,

        double difficulty
) {

    public static FsrsCardState newCard() {
        return new FsrsCardState(ReviewState.STATE_NEW, 0d, 0d);
    }

    public static FsrsCardState from(ReviewState entity) {
        if (entity == null) {
            return newCard();
        }

        return new FsrsCardState(
                entity.getState() == null ? ReviewState.STATE_NEW : entity.getState(),
                entity.getStability() == null ? 0d : entity.getStability(),
                entity.getDifficulty() == null ? 0d : entity.getDifficulty()
        );
    }

    public boolean isNew() {
        return state == ReviewState.STATE_NEW;
    }

    /**
     * 是否处于「短期内重新学习」的阶段。此阶段用短期稳定性公式，
     * 而不是基于可提取性的长期公式。
     */
    public boolean isShortTermStage() {
        return state == ReviewState.STATE_LEARNING || state == ReviewState.STATE_RELEARNING;
    }
}
