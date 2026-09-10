package com.jis.quiz.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

/**
 * 一次作答。
 *
 * @param qKey   题目业务键
 * @param answer 选择题/判断题的作答，如 B、ABD、T；挖空题留空
 * @param blanks 挖空题每个空的填写内容，顺序与题干占位符一致
 */
public record QuizAnswer(

        @NotBlank(message = "qKey 不能为空")
        String qKey,

        String answer,

        List<String> blanks
) {
}
