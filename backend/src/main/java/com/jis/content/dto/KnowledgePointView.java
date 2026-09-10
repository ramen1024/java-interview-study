package com.jis.content.dto;

/**
 * 卡片详情的完整响应：共享正文 + 当前用户个人状态。
 */
public record KnowledgePointView(

        KnowledgePointDetailVO card,

        UserCardStateVO userState
) {
}
