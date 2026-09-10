package com.jis.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 复习评分请求。
 *
 * @param rating     1 不会 / 2 模糊 / 3 会讲 / 4 轻松
 * @param durationMs 本次自评耗时，用于学习时长统计
 */
public record RateRequest(

        @Min(value = 1, message = "评分取值只能是 1~4")
        @Max(value = 4, message = "评分取值只能是 1~4")
        int rating,

        @Min(value = 0, message = "耗时不能为负")
        Integer durationMs
) {

    public int durationMsOrDefault() {
        return durationMs == null ? 0 : durationMs;
    }
}
