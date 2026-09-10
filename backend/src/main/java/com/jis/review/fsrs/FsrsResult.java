package com.jis.review.fsrs;

/**
 * 调度结果。
 *
 * @param state           新的记忆状态，取值见 {@code ReviewState.STATE_*}
 * @param stability       新的记忆稳定性（天）
 * @param difficulty      新的记忆难度（1~10）
 * @param intervalDays    距下次复习的间隔（天，已取整并加抖动）
 * @param reintroduceSoon 是否安排在同一次学习会话内重现（评分「不会」或「模糊」）
 */
public record FsrsResult(

        int state,

        double stability,

        double difficulty,

        int intervalDays,

        boolean reintroduceSoon
) {
}
