package com.jis.importer.parser;

import java.util.List;

/**
 * 一张卡片的解析结果。
 *
 * <p>字段顺序与 docs/content-spec.md 中的六段式一致：
 * 电梯版回答 → 展开讲解 → 追问链 → 常见坑 → 加分点 → 版本差异。
 */
public record ParsedCard(

        String slug,

        String title,

        String moduleSlug,

        List<String> tags,

        int difficulty,

        int frequency,

        List<ParsedRelation> relations,

        String elevatorAnswer,

        String detailMd,

        List<ParsedFollowUp> followUps,

        String pitfallsMd,

        String bonusMd,

        String versionDiffMd,

        List<ParsedQuestion> questions,

        int sort,

        String sourcePath
) {
}
