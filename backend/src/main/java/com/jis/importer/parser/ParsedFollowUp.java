package com.jis.importer.parser;

import java.util.List;

/**
 * 追问链的一个节点。
 *
 * @param qKey        编号，如 {@code Q1}、{@code Q1.1}
 * @param parentQKey  去掉最后一段编号；第一层为 null
 * @param depth       编号段数，Q1 → 1，Q1.1 → 2
 */
public record ParsedFollowUp(

        String qKey,

        String parentQKey,

        String question,

        String answerMd,

        int depth
) {
}
