package com.jis.review;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 复习调度配置，对应 {@code jis.review.*}。
 */
@ConfigurationProperties(prefix = "jis.review")
public record ReviewProperties(

        /**
         * 每天最多引入多少张新卡。设为 0 表示只复习旧卡、不引入新卡。
         * 这个配额是防止「一开始就面对 65 张新卡」而劝退的关键机制。
         */
        Integer newCardsPerDay,

        /**
         * 单次队列最多返回多少张到期卡。
         */
        Integer dueCardsPerRequest,

        /**
         * 目标保留率：希望复习时仍能回忆起来的概率。调高则间隔变短、复习更频繁。
         */
        Double desiredRetention,

        /**
         * 最长复习间隔（天）。
         */
        Integer maximumInterval,

        /**
         * 是否给间隔加随机抖动，避免大批卡片在同一天集中到期。
         */
        Boolean fuzzingEnabled,

        /**
         * 稳定性达到多少天算「已掌握」。用于统计与雷达图。
         */
        Integer masteredStabilityDays
) {

    private static final int DEFAULT_NEW_CARDS_PER_DAY = 10;
    private static final int DEFAULT_DUE_CARDS_PER_REQUEST = 30;
    private static final double DEFAULT_DESIRED_RETENTION = 0.9;
    private static final int DEFAULT_MAXIMUM_INTERVAL = 36_500;
    private static final int DEFAULT_MASTERED_STABILITY_DAYS = 21;

    public ReviewProperties {
        if (newCardsPerDay == null || newCardsPerDay < 0) {
            newCardsPerDay = DEFAULT_NEW_CARDS_PER_DAY;
        }
        if (dueCardsPerRequest == null || dueCardsPerRequest < 1) {
            dueCardsPerRequest = DEFAULT_DUE_CARDS_PER_REQUEST;
        }
        if (desiredRetention == null || desiredRetention <= 0 || desiredRetention >= 1) {
            desiredRetention = DEFAULT_DESIRED_RETENTION;
        }
        if (maximumInterval == null || maximumInterval < 1) {
            maximumInterval = DEFAULT_MAXIMUM_INTERVAL;
        }
        if (fuzzingEnabled == null) {
            fuzzingEnabled = true;
        }
        if (masteredStabilityDays == null || masteredStabilityDays < 1) {
            masteredStabilityDays = DEFAULT_MASTERED_STABILITY_DAYS;
        }
    }
}
