package com.jis.content.dto;

import java.util.List;

/**
 * 知识体系模块树节点。
 *
 * <p>只有两级，层级在导入时已确定，因此直接嵌套 children 而不做无限递归。
 */
public record ModuleTreeVO(

        String slug,

        String name,

        String description,

        String icon,

        int sort,

        /**
         * 该模块（含子模块）下的卡片总数。
         */
        int cardCount,

        List<ModuleTreeVO> children
) {
}
