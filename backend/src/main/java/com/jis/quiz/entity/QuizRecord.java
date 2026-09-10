package com.jis.quiz.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 答题记录，只追加不修改。错题本由它推导。
 */
@Data
@TableName("quiz_record")
public class QuizRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long questionId;

    /**
     * 用户实际作答。多选题会被规范化为字母升序后存储，
     * 这样同一份答案在库里的表示唯一，便于后续分析。
     */
    private String userAnswer;

    private Integer isCorrect;

    private LocalDateTime answeredAt;
}
