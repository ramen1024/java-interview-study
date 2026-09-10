package com.jis.content.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 卡片列表查询条件。全部可选，不传即不过滤。
 *
 * @param sortBy frequency 按面试热度 / difficulty 按难度 / title 按标题；默认按模块内顺序
 */
public record KnowledgePointQuery(

        String moduleSlug,

        String tag,

        Integer difficulty,

        Integer frequency,

        String sortBy,

        @Min(value = 1, message = "页码从 1 开始")
        Integer page,

        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 200, message = "每页最多 200 条")
        Integer size
) {

    public int pageOrDefault() {
        return page == null ? 1 : page;
    }

    public int sizeOrDefault() {
        return size == null ? 20 : size;
    }
}
