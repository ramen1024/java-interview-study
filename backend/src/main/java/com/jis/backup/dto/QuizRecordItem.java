package com.jis.backup.dto;

import java.time.LocalDateTime;

/**
 * 一条答题记录。
 *
 * <p>{@code answeredAt} 参与导入时的去重判定（与用户、题目一起构成业务键），
 * 缺失它就无法安全追加，导入时会被跳过。
 */
public record QuizRecordItem(

        String questionQKey,

        String userAnswer,

        boolean correct,

        LocalDateTime answeredAt
) {
}
