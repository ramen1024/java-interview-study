package com.jis.content.dto;

/**
 * 当前用户对某张卡片的个人状态。每次请求实时查，不进缓存。
 *
 * @param masteryState 0 未复习 / 1 学习中 / 2 复习中 / 3 重新学习
 * @param dueAt        下次到期时间；未复习过为 null
 * @param stability    记忆稳定性（天）；未复习过为 null
 * @param reps         累计复习次数
 * @param lapses       遗忘次数
 */
public record UserCardStateVO(

        boolean favorite,

        String noteMd,

        String noteUpdatedAt,

        Integer masteryState,

        String dueAt,

        Double stability,

        Integer reps,

        Integer lapses
) {

    public static UserCardStateVO empty() {
        return new UserCardStateVO(false, "", null, null, null, null, null, null);
    }
}
