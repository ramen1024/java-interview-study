package com.jis.content.dto;

import java.util.List;

/**
 * 卡片列表项。不含正文，用于列表与搜索结果。
 *
 * @param masteryState 0 未复习 / 1 学习中 / 2 复习中 / 3 重新学习；null 表示无复习记录
 * @param dueAt        下次到期时间，未复习过为 null
 */
public record KnowledgePointListItemVO(

        String slug,

        String title,

        String moduleSlug,

        String moduleName,

        List<String> tags,

        int difficulty,

        int frequency,

        int questionCount,

        Integer masteryState,

        String dueAt
) {
}
