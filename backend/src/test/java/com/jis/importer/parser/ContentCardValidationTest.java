package com.jis.importer.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 对 {@code content/} 下的全部卡片做一次契约校验，相当于内容侧的「编译」。
 *
 * <p>卡片由多人分批写成，格式错误如果只能靠启动应用去翻导入日志才能发现，
 * 反馈太慢、也容易被忽略。这里直接复用导入器用的 {@link MarkdownCardParser}，
 * 保证校验口径与真实导入完全一致——另写一套独立规则迟早会与解析器漂移，
 * 那时测试通过而导入失败，比没有测试更误导人。
 *
 * <p>除了「能解析」，还顺手守住几条写作契约里用文字约定的质量底线：
 * slug 全局唯一、每题必有解析、每张卡片至少 2 道题且至少一道不是 CHOICE、
 * 追问链至少 3 层。这些是内容质量的兜底，靠人工 review 44 张卡片不现实。
 *
 * <p>内容目录默认取相对本模块的 {@code ../content}，可用
 * {@code -Dcontent.root=<path>} 覆盖。
 */
class ContentCardValidationTest {

    @Test
    @DisplayName("content/ 下全部卡片都能按契约解析")
    void allCardsParse() {
        Scan scan = scan();

        assertThat(scan.failures())
                .as("以下卡片不符合 docs/content-spec.md，导入会跳过它们")
                .isEmpty();

        assertThat(scan.cards())
                .as("content/ 下一张卡片都没扫描到，检查 content.root 是否指对了")
                .isNotEmpty();
    }

    @Test
    @DisplayName("卡片 slug 全局唯一")
    void slugsAreGloballyUnique() {
        Map<String, List<String>> bySlug = new LinkedHashMap<>();

        for (ParsedCard card : scan().cards()) {
            bySlug.computeIfAbsent(card.slug(), key -> new ArrayList<>())
                    .add(card.sourcePath());
        }

        List<String> duplicated = bySlug.entrySet()
                .stream()
                .filter(entry -> entry.getValue()
                        .size() > 1)
                .map(entry -> entry.getKey() + " → " + entry.getValue())
                .toList();

        assertThat(duplicated)
                .as("slug 重复会让双链引用指向不确定的卡片，导入器也会报错")
                .isEmpty();
    }

    @Test
    @DisplayName("每张卡片至少 2 道自测题，且至少一道不是 CHOICE")
    void questionsMeetQualityBar() {
        List<String> violations = new ArrayList<>();

        for (ParsedCard card : scan().cards()) {
            if (card.questions()
                    .size() < 2) {
                violations.add(card.slug() + " 只有 " + card.questions()
                        .size() + " 道自测题");
                continue;
            }

            boolean hasNonChoice = card.questions()
                    .stream()
                    .anyMatch(question -> !"CHOICE".equals(question.type()));

            if (!hasNonChoice) {
                violations.add(card.slug() + " 的自测题全是 CHOICE，背选项没有学习价值");
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("每张卡片的追问链至少 3 层")
    void followUpDepthAtLeastThree() {
        List<String> violations = new ArrayList<>();

        for (ParsedCard card : scan().cards()) {
            int maxDepth = card.followUps()
                    .stream()
                    .mapToInt(ParsedFollowUp::depth)
                    .max()
                    .orElse(0);

            if (maxDepth < 3) {
                violations.add(card.slug() + " 的追问链最深只有 " + maxDepth + " 层");
            }
        }

        assertThat(violations)
                .as("追问链是本站的核心差异化内容，浅了就和普通八股清单没区别")
                .isEmpty();
    }

    // ------------------------------------------------------------------

    private record Scan(List<ParsedCard> cards, List<String> failures) {
    }

    /**
     * 逐文件解析，把失败收集起来而不是遇错即停——一次跑出全部问题，
     * 比修一个报一个省事得多。
     */
    private static Scan scan() {
        MarkdownCardParser parser = new MarkdownCardParser();
        List<ParsedCard> cards = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (Path file : cardFiles()) {
            try {
                String rawText = Files.readString(file, StandardCharsets.UTF_8);
                cards.add(parser.parseCard(rawText, file.toString(), 0));
            } catch (IOException e) {
                failures.add(file + " 读取失败：" + e.getMessage());
            } catch (RuntimeException e) {
                failures.add(file + "：" + e.getMessage());
            }
        }

        return new Scan(cards, failures);
    }

    private static List<Path> cardFiles() {
        Path root = contentRoot();

        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName()
                            .toString()
                            .endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path contentRoot() {
        String configured = System.getProperty("content.root");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }

        for (Path candidate : List.of(Path.of("..", "content"), Path.of("content"))) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException("找不到 content 目录，可用 -Dcontent.root=<path> 指定");
    }
}
