package com.jis.quiz.dto;

import java.util.List;

/**
 * 一道题的展示形态。
 *
 * <p><b>抽题时绝不下发答案</b>：{@code answer}、{@code blanks}、
 * {@code analysisMd} 三个字段在抽题接口里一律为 null，
 * 只有提交判分之后才随结果返回。否则前端拿到题干就同时拿到了答案。
 *
 * @param type         CHOICE / MULTI / JUDGE / CLOZE
 * @param typeLabel    题型中文说明
 * @param options      选项；非选择题为 null
 * @param blanksCount  挖空题的空数，前端据此渲染输入框
 * @param answer       正确答案；抽题时为 null
 * @param blanks       各空的可接受答案；抽题时为 null
 * @param analysisMd   解析；抽题时为 null
 */
public record QuizQuestionVO(

        String qKey,

        String type,

        String typeLabel,

        String stemMd,

        List<QuizOption> options,

        Integer blanksCount,

        int difficulty,

        String kpSlug,

        String kpTitle,

        String moduleName,

        String answer,

        List<List<String>> blanks,

        String analysisMd
) {
}
