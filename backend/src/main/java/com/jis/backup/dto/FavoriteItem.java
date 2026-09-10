package com.jis.backup.dto;

import java.time.LocalDateTime;

/**
 * 一条收藏记录。
 */
public record FavoriteItem(

        String cardSlug,

        LocalDateTime createTime
) {
}
