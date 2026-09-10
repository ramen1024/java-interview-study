package com.jis.quiz.dto;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 错题本的查询结果行。MyBatis 按属性名映射，别名需与字段名一致。
 */
@Data
public class WrongQuestionRow {

    private Long questionId;

    private LocalDateTime answeredAt;
}
