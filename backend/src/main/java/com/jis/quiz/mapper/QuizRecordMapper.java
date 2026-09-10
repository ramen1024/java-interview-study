package com.jis.quiz.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jis.quiz.dto.WrongQuestionRow;
import com.jis.quiz.entity.QuizRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface QuizRecordMapper extends BaseMapper<QuizRecord> {

    /**
     * 错题本：取「最近一次作答是错的」题目。
     *
     * <p>用「最近一次」而不是「曾经错过」，这样答对后题目会自动移出，
     * 否则错题本只增不减，很快就没人愿意看了。
     *
     * <p>判断「最近一次」用 {@code MAX(id)} 而不是 {@code MAX(answered_at)}：
     * id 是自增的，天然唯一且单调，不受同一秒内多次作答
     * （时间戳相同）导致的时间并列影响。
     */
    @Select("""
            SELECT t.question_id AS questionId,
                   t.answered_at AS answeredAt
            FROM quiz_record t
            WHERE t.user_id = #{userId}
              AND t.is_correct = 0
              AND t.id = (
                    SELECT MAX(x.id)
                    FROM quiz_record x
                    WHERE x.user_id = #{userId}
                      AND x.question_id = t.question_id
                  )
            ORDER BY t.answered_at DESC
            LIMIT #{limit}
            """)
    List<WrongQuestionRow> selectWrongQuestions(
            @Param("userId") Long userId,
            @Param("limit") int limit
    );

    /**
     * 统计错题总数，用于概览与分页。
     */
    @Select("""
            SELECT COUNT(*)
            FROM quiz_record t
            WHERE t.user_id = #{userId}
              AND t.is_correct = 0
              AND t.id = (
                    SELECT MAX(x.id)
                    FROM quiz_record x
                    WHERE x.user_id = #{userId}
                      AND x.question_id = t.question_id
                  )
            """)
    long countWrongQuestions(@Param("userId") Long userId);
}
