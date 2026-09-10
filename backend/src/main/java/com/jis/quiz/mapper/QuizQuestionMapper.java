package com.jis.quiz.mapper;

import java.util.Collection;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jis.quiz.entity.QuizQuestion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface QuizQuestionMapper extends BaseMapper<QuizQuestion> {

    /**
     * 随机抽题。
     *
     * <p>用 {@code ORDER BY RAND()}：题表规模在几百行量级，全表随机排序的
     * 代价可以忽略，而且省掉了维护随机索引的复杂度。若题目增长到几十万行，
     * 应改为「按随机 id 区间取样」或预生成打乱序列，否则 RAND() 会退化成
     * 全表扫描加排序。
     *
     * @param kpIds 为 null 或空表示不限知识点
     * @param type  为 null 或空表示不限题型
     */
    @Select("""
            <script>
            SELECT *
            FROM quiz_question
            <where>
              <if test="type != null and type != ''">
                AND type = #{type}
              </if>
              <if test="kpIds != null and kpIds.size() > 0">
                AND kp_id IN
                <foreach collection="kpIds" item="kpId" open="(" separator="," close=")">
                  #{kpId}
                </foreach>
              </if>
            </where>
            ORDER BY RAND()
            LIMIT #{limit}
            </script>
            """)
    List<QuizQuestion> selectRandom(
            @Param("kpIds") Collection<Long> kpIds,
            @Param("type") String type,
            @Param("limit") int limit
    );

    /**
     * 按业务键批量取题，用于判分与错题本。
     *
     * <p>按主键批量取题不必自己写：{@code BaseMapper#selectByIds} 已经提供，
     * 自定义同名方法会与它构成签名不兼容的重载而编译失败。
     */
    @Select("""
            <script>
            SELECT *
            FROM quiz_question
            WHERE q_key IN
            <foreach collection="qKeys" item="qKey" open="(" separator="," close=")">
              #{qKey}
            </foreach>
            </script>
            """)
    List<QuizQuestion> selectByQKeys(@Param("qKeys") Collection<String> qKeys);
}
