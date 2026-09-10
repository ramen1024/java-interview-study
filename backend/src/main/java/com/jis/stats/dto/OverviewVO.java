package com.jis.stats.dto;

/**
 * 学习概览，仪表盘顶部用它。
 *
 * @param dueCount          当前到期卡数
 * @param newCount          尚未开始复习的新卡数
 * @param remainingNewQuota 今日剩余新卡配额
 * @param reviewedToday     今日复习次数（同一张卡重现会重复计数）
 * @param quizToday         今日答题数
 * @param accuracyToday     今日正确率百分比；今日没答题时为 null
 * @param streakDays        连续学习天数
 * @param totalCards        卡片总数
 * @param trackedCards      已开始复习的卡片数
 * @param masteredCards     稳定性达标视为已掌握的卡片数
 */
public record OverviewVO(

        long dueCount,

        long newCount,

        long remainingNewQuota,

        long reviewedToday,

        long quizToday,

        Integer accuracyToday,

        int streakDays,

        long totalCards,

        long trackedCards,

        long masteredCards
) {
}
