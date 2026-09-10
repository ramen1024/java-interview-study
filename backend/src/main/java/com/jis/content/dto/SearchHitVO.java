package com.jis.content.dto;

import java.util.List;

/**
 * 搜索结果项。带一小段电梯版回答作摘要，便于在结果列表里判断要不要点进去。
 */
public record SearchHitVO(

        String slug,

        String title,

        String moduleName,

        List<String> tags,

        int difficulty,

        int frequency,

        String snippet
) {
}
