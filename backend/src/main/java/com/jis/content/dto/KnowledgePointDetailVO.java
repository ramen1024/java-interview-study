package com.jis.content.dto;

import java.util.List;

/**
 * 卡片正文详情。**这部分对所有用户相同，因此可以缓存**；
 * 与用户相关的笔记、收藏、复习进度放在 {@link UserCardStateVO}，不参与缓存。
 */
public record KnowledgePointDetailVO(

        String slug,

        String title,

        String moduleSlug,

        String moduleName,

        List<String> tags,

        int difficulty,

        int frequency,

        String elevatorAnswer,

        String detailMd,

        List<FollowUpVO> followUps,

        String pitfallsMd,

        String bonusMd,

        String versionDiffMd,

        List<RelatedKpVO> related,

        int questionCount,

        String updatedAt
) {
}
