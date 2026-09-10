package com.jis.quiz.dto;

import java.util.List;

/**
 * 单题判分结果。
 *
 * @param userAnswer     用户实际作答（多选会被规范化为字母升序）
 * @param correctAnswer  正确答案；挖空题为 null
 * @param blankResults   挖空题每个空是否答对；非挖空题为 null
 * @param correct        整题是否答对（挖空题要求每个空都对）
 */
public record QuizResultVO(

        String qKey,

        String type,

        boolean correct,

        String userAnswer,

        String correctAnswer,

        List<Boolean> blankResults,

        List<List<String>> blanks,

        String analysisMd
) {
}
