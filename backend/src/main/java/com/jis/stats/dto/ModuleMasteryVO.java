package com.jis.stats.dto;

import java.util.List;

/**
 * 模块掌握度，雷达图的每根轴。
 *
 * <p>父模块的数值包含所有子模块，同时通过 children 保留明细，
 * 前端可以在同一个响应里既画雷达图又渲染展开的明细列表。
 *
 * @param masteryRate 掌握率百分比（0~100），保留整数
 */
public record ModuleMasteryVO(

        String slug,

        String name,

        int total,

        int started,

        int mastered,

        int masteryRate,

        List<ModuleMasteryVO> children
) {
}
