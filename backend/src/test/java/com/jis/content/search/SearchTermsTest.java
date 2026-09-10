package com.jis.content.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 搜索切词的单元测试。
 *
 * <p>这里守的是一条容易反复踩的规则：ngram_token_size 为 2 时，把无空格的中文
 * 复合词整串丢给布尔模式，等价于要求这几个字连续出现。标题「缓存穿透、击穿、雪崩」
 * 因为中间隔着顿号搜不到，却有一张恰好连排出现过该词组的次要卡片命中，
 * 于是第一级检索非空即返回，最该被看到的卡片被挡在结果之外。
 * 切词错了不会报任何异常，只会让搜索结果悄悄变差，所以要靠测试钉住。
 */
class SearchTermsTest {

    @Test
    @DisplayName("无空格的中文复合词按双字切分")
    void splitsCjkCompoundIntoBigrams() {
        assertThat(SearchTerms.split("缓存击穿"))
                .containsExactly("缓存", "击穿");
    }

    @Test
    @DisplayName("切出的词各自成为必需词，不再要求相邻")
    void buildsBooleanQueryWithRequiredTerms() {
        assertThat(SearchTerms.toBooleanQuery("缓存击穿"))
                .isEqualTo("+缓存 +击穿");
    }

    @Test
    @DisplayName("两字词保持原样")
    void keepsTwoCharacterWordIntact() {
        assertThat(SearchTerms.split("扩容")).containsExactly("扩容");
    }

    @Test
    @DisplayName("奇数长度末尾剩三字时整块保留，避免产生无法被索引的单字词")
    void keepsTrailingTripleTogether() {
        assertThat(SearchTerms.split("布隆过滤器"))
                .containsExactly("布隆", "过滤器");
    }

    @Test
    @DisplayName("已有空格的输入按空格与双字双重切分")
    void handlesSpacedInput() {
        assertThat(SearchTerms.split("缓存 击穿"))
                .containsExactly("缓存", "击穿");
    }

    @Test
    @DisplayName("ASCII 词整段保留，不被切开")
    void keepsAsciiWordWhole() {
        assertThat(SearchTerms.split("HashMap")).containsExactly("HashMap");
        assertThat(SearchTerms.split("JVM调优")).containsExactly("JVM", "调优");
        assertThat(SearchTerms.split("HashMap 扩容")).containsExactly("HashMap", "扩容");
    }

    @Test
    @DisplayName("标点被丢弃，不会变成匹配不到任何文档的必需词")
    void dropsPunctuation() {
        assertThat(SearchTerms.split("缓存、击穿"))
                .containsExactly("缓存", "击穿");
        assertThat(SearchTerms.toBooleanQuery("\"缓存击穿\""))
                .isEqualTo("+缓存 +击穿");
    }

    @Test
    @DisplayName("连字符等词内符号不切断 ASCII 词")
    void keepsInnerHyphen() {
        assertThat(SearchTerms.split("Cache-Aside")).containsExactly("Cache-Aside");
    }

    @Test
    @DisplayName("长中文串依次两字切分")
    void splitsLongerCjkRun() {
        assertThat(SearchTerms.split("垃圾回收算法"))
                .containsExactly("垃圾", "回收", "算法");
    }

    @Test
    @DisplayName("空值、空白与纯标点都切不出检索词")
    void blankInputYieldsNoTerms() {
        assertThat(SearchTerms.split(null)).isEmpty();
        assertThat(SearchTerms.split("   ")).isEmpty();
        assertThat(SearchTerms.split("、，。！")).isEmpty();
        assertThat(SearchTerms.toBooleanQuery("、，。")).isEmpty();
    }

    @Test
    @DisplayName("用户输入里的布尔操作符不会破坏查询串结构")
    void booleanOperatorsAreNeutralized() {
        // 每个词都带 + 前缀，用户输入的 + - ~ * 要么被当成标点丢掉，要么留在词内
        assertThat(SearchTerms.toBooleanQuery("+缓存 -击穿"))
                .isEqualTo("+缓存 +击穿");
        assertThat(SearchTerms.toBooleanQuery("缓存~击穿"))
                .isEqualTo("+缓存 +击穿");
    }
}
