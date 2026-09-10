package com.jis.content.search;

import java.util.ArrayList;
import java.util.List;

/**
 * 把用户输入的关键词切成 ngram 全文索引可用的「必需词」。
 *
 * <p>背景：{@code ngram_token_size} 是 2，MySQL 会把查询串切成 bigram 后要求它们在
 * 文档里连续出现，效果等同于短语匹配。于是一个没有空格的中文复合词「缓存击穿」
 * 会被当成「这四个字必须连排」，而标题写作「缓存穿透、击穿、雪崩」的卡片反而搜不到；
 * 命中的是一张恰好出现过「缓存击穿」连排的次要卡片，第一级检索非空即返回，
 * 真正该排第一的卡片就这样被挡在结果之外。
 *
 * <p>这里按 ngram 的粒度把中文串切成**不重叠的双字词**并各自作为必需词：
 * 「缓存击穿」变成 {@code +缓存 +击穿}。既保留「两个词都要出现」的精度，
 * 又不再要求二者相邻，因此对中文断句、标点分隔都成立。
 *
 * <p>两个细节：
 * <ul>
 *   <li>切完若末尾只剩单字，就并进上一块（中文单字无法被 ngram_token_size=2 的索引
 *       命中，单独留下会让整个与查询什么都匹配不到）</li>
 *   <li>非中文片段（HashMap、JVM）整段保留——ngram 解析器把连续的 ASCII 字母数字
 *       当作一个词，切开反而破坏它</li>
 * </ul>
 *
 * <p>标点与其他非字母数字的片段会被丢弃，所以用户输入里的引号、括号、{@code + - ~ *}
 * 等布尔模式操作符天然构不成威胁，不需要额外的转义步骤。
 */
public final class SearchTerms {

    private SearchTerms() {
    }

    /**
     * 切出全部必需词。返回空列表表示这个关键词没有可用的检索词
     * （例如整串都是标点），调用方应当跳过布尔检索这一级。
     */
    public static List<String> split(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }

        List<String> terms = new ArrayList<>();
        for (String token : keyword.split("\\s+")) {
            if (!token.isEmpty()) {
                terms.addAll(splitToken(token));
            }
        }

        return terms;
    }

    /**
     * 构造布尔模式查询串，每个词加 {@code +} 成为必需词，词间即「与」。
     * 没有可用检索词时返回空串。
     */
    public static String toBooleanQuery(String keyword) {
        List<String> terms = split(keyword);
        if (terms.isEmpty()) {
            return "";
        }

        return terms.stream()
                .map(term -> "+" + term)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    // ------------------------------------------------------------------

    /**
     * 按书写系统把一个 token 切成若干片段：中文片段再按双字词切分，
     * 非中文片段整段保留（前提是含有字母或数字，纯标点直接丢弃）。
     */
    private static List<String> splitToken(String token) {
        List<String> terms = new ArrayList<>();
        StringBuilder cjkRun = new StringBuilder();
        StringBuilder otherRun = new StringBuilder();

        int index = 0;
        while (index < token.length()) {
            int codePoint = token.codePointAt(index);
            if (isCjk(codePoint)) {
                flushOther(otherRun, terms);
                cjkRun.appendCodePoint(codePoint);
            } else {
                flushCjk(cjkRun, terms);
                otherRun.appendCodePoint(codePoint);
            }

            index += Character.charCount(codePoint);
        }

        flushCjk(cjkRun, terms);
        flushOther(otherRun, terms);

        return terms;
    }

    private static void flushCjk(StringBuilder run, List<String> terms) {
        if (run.isEmpty()) {
            return;
        }

        String text = run.toString();
        run.setLength(0);
        terms.addAll(chunkCjk(text));
    }

    private static void flushOther(StringBuilder run, List<String> terms) {
        if (run.isEmpty()) {
            return;
        }

        String text = run.toString();
        run.setLength(0);

        // 纯标点（、，（）" 等）不参与检索，留着会变成匹配不到任何文档的必需词，
        // 把整个与查询拖成空结果
        if (text.codePoints()
                .anyMatch(Character::isLetterOrDigit)) {
            terms.add(text);
        }
    }

    /**
     * 中文串按 ngram_token_size(2) 切成不重叠的双字词；末尾剩 3 个字时整块保留，
     * 剩 1 个字时并进上一块，避免出现无法被索引的单字词。
     */
    private static List<String> chunkCjk(String text) {
        int length = text.length();
        if (length <= 2) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        int index = 0;
        while (index < length) {
            int remaining = length - index;
            if (remaining <= 3) {
                chunks.add(text.substring(index));
                break;
            }

            chunks.add(text.substring(index, index + 2));
            index += 2;
        }

        return chunks;
    }

    private static boolean isCjk(int codePoint) {
        return switch (Character.UnicodeScript.of(codePoint)) {
            case HAN, HIRAGANA, KATAKANA, HANGUL, BOPOMOFO -> true;
            default -> false;
        };
    }
}
