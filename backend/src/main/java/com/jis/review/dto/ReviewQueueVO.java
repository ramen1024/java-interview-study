package com.jis.review.dto;

import java.util.List;

import com.jis.content.dto.KnowledgePointListItemVO;

/**
 * 今日复习队列。
 *
 * <p>队列由两部分组成，这也是 Anki 的模型：
 * <b>到期卡</b>（有复习记录且已到期）与<b>新卡</b>（还没有任何复习记录）。
 * 新卡每日有配额，避免一次性面对全部内容。
 *
 * @param items             实际返回的卡片，到期卡在前（最逾期的优先）
 * @param dueCount          当前到期的卡片总数（可能大于 items 中到期卡的条数）
 * @param newCount          本次纳入的新卡数
 * @param remainingNewQuota 今日还剩多少新卡配额
 */
public record ReviewQueueVO(

        List<KnowledgePointListItemVO> items,

        long dueCount,

        long newCount,

        long remainingNewQuota
) {
}
