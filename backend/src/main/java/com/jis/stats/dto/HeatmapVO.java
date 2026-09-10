package com.jis.stats.dto;

import java.util.List;

/**
 * 年度热力图。
 *
 * @param year        年份
 * @param totalCount  全年复习 + 答题总次数
 * @param activeDays  有学习记录的天数
 * @param days        只包含有记录的日子；前端自行补齐空白格
 */
public record HeatmapVO(

        int year,

        int totalCount,

        int activeDays,

        List<HeatmapDayVO> days
) {
}
