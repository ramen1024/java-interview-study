package com.jis.stats.dto;

/**
 * 热力图的单日数据。
 *
 * @param date       日期，格式 yyyy-MM-dd
 * @param count      当日复习次数 + 答题数
 * @param reviewCount 当日复习次数
 * @param quizCount  当日答题数
 * @param level      强度等级 0~4，由前端按分位数着色；这里先按固定阈值给出
 */
public record HeatmapDayVO(

        String date,

        int count,

        int reviewCount,

        int quizCount,

        int level
) {
}
