package com.jis.quiz.dto;

import java.util.List;

/**
 * 整批判分结果。
 *
 * @param total         总题数
 * @param correctCount  答对题数
 * @param accuracy      正确率百分比，保留整数
 */
public record QuizSubmitResultVO(

        int total,

        int correctCount,

        int accuracy,

        List<QuizResultVO> results
) {
}
