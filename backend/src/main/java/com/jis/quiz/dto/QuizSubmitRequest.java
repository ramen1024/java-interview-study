package com.jis.quiz.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 批量提交作答。整批一次提交而不是逐题提交，原因有两个：
 * 少一次往返；以及用户中途退出不会留下半截答题记录。
 */
public record QuizSubmitRequest(

        @NotEmpty(message = "至少提交一道题")
        @Size(max = 100, message = "单次最多提交 100 道题")
        List<@Valid QuizAnswer> answers,

        /**
         * 本次答题总耗时（秒），计入学习时长统计。
         */
        Integer durationSec
) {

    public int durationSecOrDefault() {
        return durationSec == null ? 0 : durationSec;
    }
}
