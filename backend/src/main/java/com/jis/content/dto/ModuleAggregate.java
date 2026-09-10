package com.jis.content.dto;

import lombok.Data;

/**
 * 按模块聚合的复习统计结果。MyBatis 按属性名映射，
 * 所以这里的字段名必须与 SQL 里的别名一致。
 */
@Data
public class ModuleAggregate {

    private Long moduleId;

    private Long total;

    private Long mastered;

    /**
     * 已开始复习的卡片数（有 review_state 记录）。
     */
    private Long started;
}
