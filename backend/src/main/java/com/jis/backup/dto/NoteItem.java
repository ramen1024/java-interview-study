package com.jis.backup.dto;

import java.time.LocalDateTime;

/**
 * 一张卡片上的用户笔记。
 */
public record NoteItem(

        String cardSlug,

        String contentMd,

        LocalDateTime updateTime
) {
}
