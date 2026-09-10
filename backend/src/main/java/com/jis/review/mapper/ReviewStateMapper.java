package com.jis.review.mapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jis.review.entity.ReviewState;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ReviewStateMapper extends BaseMapper<ReviewState> {

    /**
     * 取出到期的卡片 id，最逾期的排在最前。
     *
     * <p>必须用 JOIN 而不是「先查到期记录再过滤模块」：后者会让
     * {@code LIMIT} 作用在过滤之前，按模块筛选时可能返回不足量的卡片。
     *
     * @param moduleIds 为 null 表示不按模块过滤
     */
    @Select("""
            <script>
            SELECT rs.kp_id
            FROM review_state rs
            JOIN knowledge_point kp ON kp.id = rs.kp_id
            WHERE rs.user_id = #{userId}
              AND rs.due_at &lt;= #{now}
              <if test="moduleIds != null and moduleIds.size() > 0">
                AND kp.module_id IN
                <foreach collection="moduleIds" item="moduleId" open="(" separator="," close=")">
                  #{moduleId}
                </foreach>
              </if>
            ORDER BY rs.due_at ASC, rs.id ASC
            LIMIT #{limit}
            </script>
            """)
    List<Long> selectDueKpIds(
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now,
            @Param("moduleIds") Collection<Long> moduleIds,
            @Param("limit") int limit
    );

    /**
     * 统计到期卡总数。与 {@link #selectDueKpIds} 用同样的过滤条件，
     * 否则前端会看到「共 30 张待复习」但列表只有 3 张这种对不上的数字。
     */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM review_state rs
            JOIN knowledge_point kp ON kp.id = rs.kp_id
            WHERE rs.user_id = #{userId}
              AND rs.due_at &lt;= #{now}
              <if test="moduleIds != null and moduleIds.size() > 0">
                AND kp.module_id IN
                <foreach collection="moduleIds" item="moduleId" open="(" separator="," close=")">
                  #{moduleId}
                </foreach>
              </if>
            </script>
            """)
    long countDue(
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now,
            @Param("moduleIds") Collection<Long> moduleIds
    );

    /**
     * 今日已引入的新卡数。以 review_state 的创建时间判定，
     * 因为它只在用户首次评分时才创建。
     *
     * <p>注意：这条 SQL 没有包在 {@code <script>} 里，所以比较运算符必须写成
     * 字面量 {@code >=}。只有 {@code <script>} 块内才按 XML 解析、需要
     * {@code &lt;=} 这类实体——两者混用会生成字面量 {@code &gt;=} 导致语法错误。
     */
    @Select("""
            SELECT COUNT(*)
            FROM review_state
            WHERE user_id = #{userId}
              AND create_time >= #{since}
            """)
    long countIntroducedSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);
}
