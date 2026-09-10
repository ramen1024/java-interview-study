package com.jis.review.dto;

/**
 * 评分后的调度结果。把内部状态翻译成人能看懂的字段，
 * 前端可以直接展示「下次复习：3 天后」并解释为什么。
 *
 * @param rating              本次评分
 * @param ratingLabel         评分的中文说明
 * @param state               新的记忆状态，取值见 ReviewState.STATE_*
 * @param stateLabel          状态的中文说明
 * @param stabilityBefore     评分前的稳定性（天）；首次复习为 null
 * @param stabilityAfter      评分后的稳定性（天）
 * @param difficultyAfter     评分后的记忆难度（1~10）
 * @param intervalDays        距下次复习的天数；本轮内重现时为 0
 * @param reintroduceSoon     是否安排在本轮内重现
 * @param dueAt               下次到期时间
 */
public record RateResultVO(

        int rating,

        String ratingLabel,

        int state,

        String stateLabel,

        Double stabilityBefore,

        double stabilityAfter,

        double difficultyAfter,

        int intervalDays,

        boolean reintroduceSoon,

        String dueAt
) {
}
