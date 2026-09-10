package com.jis.backup.dto;

import java.time.LocalDate;

/**
 * 某一天的累计学习统计。导入时按 (user, statDate) 覆盖为备份中的绝对值，
 * 而不是累加，否则重复导入会把热力图数字翻倍。
 */
public record StudyDailyItem(

        LocalDate statDate,

        int reviewCount,

        int newCount,

        int quizCount,

        int correctCount,

        int durationSec
) {
}
