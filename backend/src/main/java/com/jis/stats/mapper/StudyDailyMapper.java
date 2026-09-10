package com.jis.stats.mapper;

import java.time.LocalDate;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jis.stats.entity.StudyDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface StudyDailyMapper extends BaseMapper<StudyDaily> {

    /**
     * 累加当天的复习量。
     *
     * <p>用 {@code INSERT ... ON DUPLICATE KEY UPDATE} 而不是「先查后改」：
     * 后者在并发下会丢计数，而且多一次往返。SQL 里的加法是原子的。
     *
     * <p>两个必须注意的语法细节：
     *
     * <ol>
     *   <li>用 MySQL 8.0.19+ 的行别名语法（{@code AS new}）引用待插入的值，
     *       而不是已废弃的 {@code VALUES()} 函数。</li>
     *   <li>UPDATE 子句右侧的被加数必须用**表名限定**写成
     *       {@code study_daily.review_count}。只写 {@code review_count}
     *       会让 MySQL 报 "Column 'review_count' in field list is ambiguous"——
     *       因为行别名 {@code new} 也有同名列，无法判断指的是哪一个。</li>
     * </ol>
     */
    @Insert("""
            INSERT INTO study_daily
                (user_id, stat_date, review_count, new_count, quiz_count, correct_count, duration_sec)
            VALUES
                (#{userId}, #{statDate}, #{reviewDelta}, #{newDelta}, 0, 0, #{durationSec}) AS new
            ON DUPLICATE KEY UPDATE
                review_count = study_daily.review_count + new.review_count,
                new_count    = study_daily.new_count + new.new_count,
                duration_sec = study_daily.duration_sec + new.duration_sec
            """)
    int accumulateReview(
            @Param("userId") Long userId,
            @Param("statDate") LocalDate statDate,
            @Param("reviewDelta") int reviewDelta,
            @Param("newDelta") int newDelta,
            @Param("durationSec") int durationSec
    );

    /**
     * 累加当天的答题量。
     */
    @Insert("""
            INSERT INTO study_daily
                (user_id, stat_date, review_count, new_count, quiz_count, correct_count, duration_sec)
            VALUES
                (#{userId}, #{statDate}, 0, 0, #{quizDelta}, #{correctDelta}, #{durationSec}) AS new
            ON DUPLICATE KEY UPDATE
                quiz_count    = study_daily.quiz_count + new.quiz_count,
                correct_count = study_daily.correct_count + new.correct_count,
                duration_sec  = study_daily.duration_sec + new.duration_sec
            """)
    int accumulateQuiz(
            @Param("userId") Long userId,
            @Param("statDate") LocalDate statDate,
            @Param("quizDelta") int quizDelta,
            @Param("correctDelta") int correctDelta,
            @Param("durationSec") int durationSec
    );

    /**
     * 取某年的每日统计，供热力图渲染。
     */
    @Select("""
            SELECT *
            FROM study_daily
            WHERE user_id = #{userId}
              AND stat_date BETWEEN #{start} AND #{end}
            ORDER BY stat_date ASC
            """)
    List<StudyDaily> selectBetween(
            @Param("userId") Long userId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end
    );

    /**
     * 取最近若干天的记录，用于计算连续打卡天数。
     */
    @Select("""
            SELECT *
            FROM study_daily
            WHERE user_id = #{userId}
              AND stat_date >= #{since}
              AND (review_count > 0 OR quiz_count > 0)
            ORDER BY stat_date DESC
            """)
    List<StudyDaily> selectActiveSince(
            @Param("userId") Long userId,
            @Param("since") LocalDate since
    );
}
