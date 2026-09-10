package com.jis.content.mapper;

import java.util.Collection;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jis.content.dto.ModuleAggregate;
import com.jis.content.entity.KnowledgePoint;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface KnowledgePointMapper extends BaseMapper<KnowledgePoint> {

    /**
     * 布尔模式的「必需词」检索，精度优先。
     *
     * <p>ngram 分词下，{@code +词} 要求该词必须出现，多个 {@code +词} 之间是
     * 「与」语义。这比自然语言模式精确得多——自然语言模式是 bigram 或匹配，
     * 搜「缓存击穿」会把只提到「缓存」的 JVM 卡片也带出来。
     *
     * <p>查询串由 {@code ContentQueryService#toRequiredTermsQuery} 构造。
     */
    @Select("""
            SELECT *
            FROM knowledge_point
            WHERE MATCH(title, elevator_answer, detail_md, tags) AGAINST(#{booleanQuery} IN BOOLEAN MODE)
            ORDER BY MATCH(title, elevator_answer, detail_md, tags) AGAINST(#{booleanQuery} IN BOOLEAN MODE) DESC
            LIMIT #{limit}
            """)
    List<KnowledgePoint> searchByPhrase(
            @Param("booleanQuery") String booleanQuery,
            @Param("limit") int limit
    );

    /**
     * 自然语言模式，作为短语检索无结果时的召回兜底。
     */
    @Select("""
            SELECT *
            FROM knowledge_point
            WHERE MATCH(title, elevator_answer, detail_md, tags) AGAINST(#{keyword} IN NATURAL LANGUAGE MODE)
            ORDER BY MATCH(title, elevator_answer, detail_md, tags) AGAINST(#{keyword} IN NATURAL LANGUAGE MODE) DESC
            LIMIT #{limit}
            """)
    List<KnowledgePoint> searchByKeyword(
            @Param("keyword") String keyword,
            @Param("limit") int limit
    );

    /**
     * 短查询串（长度 < ngram_token_size）的回落方案。
     */
    @Select("""
            SELECT *
            FROM knowledge_point
            WHERE title LIKE CONCAT('%', #{keyword}, '%')
               OR tags LIKE CONCAT('%', #{keyword}, '%')
            ORDER BY frequency DESC, sort ASC
            LIMIT #{limit}
            """)
    List<KnowledgePoint> searchByLike(
            @Param("keyword") String keyword,
            @Param("limit") int limit
    );

    /**
     * 取出「新卡」：该用户还没有任何复习记录的卡片。
     *
     * <p>用 NOT EXISTS 而不是 LEFT JOIN ... IS NULL，因为前者可以在
     * 命中 uk_review_state_user_kp 索引后立即短路，不需要构造连接结果。
     *
     * <p>排序上把高频卡排前面：新卡配额有限，应该先复习最容易考到的内容。
     */
    @Select("""
            <script>
            SELECT kp.id
            FROM knowledge_point kp
            WHERE NOT EXISTS (
                    SELECT 1
                    FROM review_state rs
                    WHERE rs.user_id = #{userId}
                      AND rs.kp_id = kp.id
                  )
              <if test="moduleIds != null and moduleIds.size() > 0">
                AND kp.module_id IN
                <foreach collection="moduleIds" item="moduleId" open="(" separator="," close=")">
                  #{moduleId}
                </foreach>
              </if>
            ORDER BY kp.frequency DESC, kp.sort ASC, kp.id ASC
            LIMIT #{limit}
            </script>
            """)
    List<Long> selectNewKpIds(
            @Param("userId") Long userId,
            @Param("moduleIds") Collection<Long> moduleIds,
            @Param("limit") int limit
    );

    /**
     * 新卡总数，用于计算今日剩余配额。
     */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM knowledge_point kp
            WHERE NOT EXISTS (
                    SELECT 1
                    FROM review_state rs
                    WHERE rs.user_id = #{userId}
                      AND rs.kp_id = kp.id
                  )
              <if test="moduleIds != null and moduleIds.size() > 0">
                AND kp.module_id IN
                <foreach collection="moduleIds" item="moduleId" open="(" separator="," close=")">
                  #{moduleId}
                </foreach>
              </if>
            </script>
            """)
    long countNew(@Param("userId") Long userId, @Param("moduleIds") Collection<Long> moduleIds);

    /**
     * 按模块聚合卡片总数与掌握情况，供雷达图使用。
     *
     * <p>用 LEFT JOIN 保证「一张卡都没复习过的模块」也会返回一行，
     * 否则雷达图会缺角而不是显示为 0。
     */
    @Select("""
            SELECT kp.module_id                             AS moduleId,
                   COUNT(*)                                 AS total,
                   SUM(CASE WHEN rs.stability >= #{masteredStability} THEN 1 ELSE 0 END) AS mastered,
                   SUM(CASE WHEN rs.id IS NOT NULL THEN 1 ELSE 0 END)                    AS started
            FROM knowledge_point kp
            LEFT JOIN review_state rs
                   ON rs.kp_id = kp.id AND rs.user_id = #{userId}
            GROUP BY kp.module_id
            """)
    List<ModuleAggregate> aggregateByModule(
            @Param("userId") Long userId,
            @Param("masteredStability") double masteredStability
    );
}
