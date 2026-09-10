package com.jis.importer.parser;

import java.util.List;
import java.util.Map;

/**
 * 一道自测题。
 *
 * @param qKey        全局稳定键 {@code <卡片slug>-<序号>}
 * @param options     选项，key 为 A/B/C/D，仅选择题有值
 * @param answer      CHOICE 为 A、MULTI 为 ABC、JUDGE 为 T/F；CLOZE 为 null
 * @param blanks      代码挖空题每空的可接受答案；非挖空题为 null
 * @param difficulty  1~3
 */
public record ParsedQuestion(

        String qKey,

        String type,

        String stemMd,

        Map<String, String> options,

        String answer,

        List<List<String>> blanks,

        String analysisMd,

        int difficulty
) {
}
