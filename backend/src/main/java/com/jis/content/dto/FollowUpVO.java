package com.jis.content.dto;

import java.util.List;

/**
 * 追问链节点（树形）。
 *
 * <p>前端可逐层展开：先自己想第一层怎么答，再展开看参考答案，
 * 然后进入下一层追问。这是本站与普通八股题库的核心差别。
 */
public record FollowUpVO(

        String qKey,

        String question,

        String answerMd,

        int depth,

        List<FollowUpVO> children
) {
}
