package com.jis.content.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
}
