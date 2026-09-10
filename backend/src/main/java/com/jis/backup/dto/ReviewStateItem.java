package com.jis.backup.dto;

import java.time.LocalDateTime;

/**
 * 一张卡片的 FSRS 记忆状态。
 *
 * @param state        0 New / 1 Learning / 2 Review / 3 Relearning
 * @param stability    记忆稳定性 S（天）
 * @param difficulty   记忆难度 D（1~10）
 * @param dueAt        下次到期时间；库表中为 NOT NULL
 * @param lastReviewAt 上次复习时间；从未复习过为 null
 */
public record ReviewStateItem(

        String cardSlug,

        int state,

        double stability,

        double difficulty,

        int reps,

        int lapses,

        LocalDateTime dueAt,

        LocalDateTime lastReviewAt
) {
}
