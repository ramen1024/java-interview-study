package com.jis.importer.parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 把 {@code content/} 下的 Markdown 卡片解析成 {@link ParsedCard}。
 *
 * <p>格式契约见 docs/content-spec.md。解析器的设计取向是**尽早失败**：
 * 章节缺失、编号断层、选项与答案不一致等作者笔误，都在这里报出带文件名的
 * 明确错误，而不是让一条脏数据悄悄进库。
 */
@Component
public class MarkdownCardParser {

    private static final String FRONT_MATTER_DELIMITER = "---";

    private static final String SECTION_ELEVATOR = "电梯版回答";
    private static final String SECTION_DETAIL = "展开讲解";
    private static final String SECTION_FOLLOW_UP = "追问链";
    private static final String SECTION_PITFALLS = "常见坑";
    private static final String SECTION_BONUS = "加分点";
    private static final String SECTION_VERSION = "版本差异";
    private static final String SECTION_QUIZ = "自测题";

    /**
     * 追问链节点：### Q1: 问题 / #### Q1.1: 问题 / ##### Q1.1.1: 问题。
     * 层级由 # 的个数表达，必须与编号段数一致，否则视为作者笔误。
     */
    private static final Pattern FOLLOW_UP_HEADING =
            Pattern.compile("^(#{3,5})\\s+(Q[0-9]+(?:\\.[0-9]+)*)\\s*[:：]\\s*(.+?)\\s*$");

    private static final Pattern SECTION_HEADING = Pattern.compile("^##\\s+(.+?)\\s*$");

    /**
     * 围栏起始行。长度可变（``` 或 ````），闭合必须用不短于它的同种字符围栏，
     * 这样挖空题的题干里才能内嵌 ```java 代码块。
     */
    private static final Pattern FENCE_OPEN = Pattern.compile("^\\s*(`{3,}|~{3,})");
    private static final Pattern YAML_FENCE_OPEN = Pattern.compile("^\\s*(`{3,}|~{3,})\\s*yaml\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern CLOZE_PLACEHOLDER = Pattern.compile("\\{\\{(\\d+)}}");

    private static final Set<String> ALLOWED_QUESTION_TYPES = Set.of("CHOICE", "MULTI", "JUDGE", "CLOZE");

    private final Yaml yaml;

    public MarkdownCardParser() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setCodePointLimit(16 * 1024 * 1024);

        // SafeConstructor：不使用能够实例化任意类的构造器，杜绝 YAML 反序列化风险
        this.yaml = new Yaml(new SafeConstructor(loaderOptions));
    }

    // ------------------------------------------------------------------
    // 卡片
    // ------------------------------------------------------------------

    public ParsedCard parseCard(String rawText, String sourcePath, int sort) {
        String normalized = rawText.replace("\r\n", "\n")
                .replace('\r', '\n');

        FrontMatterSplit split = splitFrontMatter(normalized, sourcePath);
        Map<String, Object> meta = split.meta();
        Map<String, List<String>> sections = splitSections(split.body(), sourcePath);

        String slug = requireText(meta, "slug", sourcePath, "frontmatter");
        String title = requireText(meta, "title", sourcePath, "frontmatter");
        String moduleSlug = requireText(meta, "module", sourcePath, "frontmatter");

        validateSlug(slug, sourcePath);

        List<ParsedFollowUp> followUps = parseFollowUps(
                sectionLines(sections, SECTION_FOLLOW_UP),
                sourcePath
        );

        return new ParsedCard(
                slug,
                title,
                moduleSlug,
                readStringList(meta.get("tags"), sourcePath, "tags"),
                readBoundedInt(meta.get("difficulty"), 2, 1, 3, sourcePath, "difficulty"),
                readBoundedInt(meta.get("frequency"), 2, 1, 3, sourcePath, "frequency"),
                readRelations(meta.get("related"), sourcePath),
                requireSectionText(sections, SECTION_ELEVATOR, sourcePath),
                sectionText(sections, SECTION_DETAIL),
                followUps,
                sectionText(sections, SECTION_PITFALLS),
                sectionText(sections, SECTION_BONUS),
                sectionText(sections, SECTION_VERSION),
                parseQuestions(sectionLines(sections, SECTION_QUIZ), slug, sourcePath),
                sort,
                sourcePath
        );
    }

    // ------------------------------------------------------------------
    // 模块
    // ------------------------------------------------------------------

    public ParsedModule parseModule(
            String rawText,
            String sourcePath,
            String directoryPath,
            String parentSlug,
            int level
    ) {
        Map<String, Object> meta = loadYamlMap(
                rawText.replace("\r\n", "\n"),
                sourcePath,
                "模块定义"
        );

        String slug = requireText(meta, "slug", sourcePath, "模块定义");
        String name = requireText(meta, "name", sourcePath, "模块定义");
        validateSlug(slug, sourcePath);

        return new ParsedModule(
                slug,
                name,
                readOptionalText(meta.get("description")),
                readOptionalText(meta.get("icon")),
                readBoundedInt(meta.get("sort"), 0, 0, 100_000, sourcePath, "sort"),
                parentSlug,
                level,
                directoryPath
        );
    }

    // ------------------------------------------------------------------
    // frontmatter 与章节
    // ------------------------------------------------------------------

    private FrontMatterSplit splitFrontMatter(String text, String sourcePath) {
        String[] lines = text.split("\n", -1);

        int cursor = 0;
        while (cursor < lines.length && lines[cursor].isBlank()) {
            cursor++;
        }

        if (cursor >= lines.length || !FRONT_MATTER_DELIMITER.equals(lines[cursor].trim())) {
            throw new CardParseException(sourcePath, "文件必须以 YAML frontmatter（首行 ---）开始");
        }

        int end = -1;
        for (int i = cursor + 1; i < lines.length; i++) {
            if (FRONT_MATTER_DELIMITER.equals(lines[i].trim())) {
                end = i;
                break;
            }
        }

        if (end < 0) {
            throw new CardParseException(sourcePath, "frontmatter 缺少结束的 ---");
        }

        String yamlText = String.join("\n", Arrays.copyOfRange(lines, cursor + 1, end));
        String body = String.join("\n", Arrays.copyOfRange(lines, end + 1, lines.length));

        return new FrontMatterSplit(loadYamlMap(yamlText, sourcePath, "frontmatter"), body);
    }

    /**
     * 按 {@code ## } 标题切分章节，并跳过围栏代码块——否则代码块里以 ## 开头的
     * 注释会被误判成新章节。
     */
    private Map<String, List<String>> splitSections(String body, String sourcePath) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        List<String> currentLines = null;
        String currentName = null;
        FenceTracker fence = new FenceTracker();

        for (String line : body.split("\n", -1)) {
            boolean fenceLine = fence.accept(line);

            if (!fence.inside() && !fenceLine) {
                Matcher matcher = SECTION_HEADING.matcher(line);
                if (matcher.matches()) {
                    currentName = matcher.group(1)
                            .trim();
                    if (sections.containsKey(currentName)) {
                        // 重复章节以先出现的为准会导致内容静默丢失，直接报错
                        throw new CardParseException(
                                sourcePath,
                                "章节「## " + currentName + "」重复出现"
                        );
                    }
                    currentLines = new ArrayList<>();
                    sections.put(currentName, currentLines);
                    continue;
                }
            }

            if (currentLines != null) {
                currentLines.add(line);
            }
        }

        return sections;
    }

    private String requireSectionText(
            Map<String, List<String>> sections,
            String sectionName,
            String sourcePath
    ) {
        String text = sectionText(sections, sectionName);

        if (text.isBlank()) {
            throw new CardParseException(sourcePath, "缺少必需章节「## " + sectionName + "」或该章节为空");
        }

        return text;
    }

    private String sectionText(Map<String, List<String>> sections, String sectionName) {
        List<String> lines = sections.get(sectionName);

        return lines == null ? "" : String.join("\n", lines)
                .strip();
    }

    private List<String> sectionLines(Map<String, List<String>> sections, String sectionName) {
        return sections.getOrDefault(sectionName, List.of());
    }

    // ------------------------------------------------------------------
    // 追问链
    // ------------------------------------------------------------------

    private List<ParsedFollowUp> parseFollowUps(List<String> lines, String sourcePath) {
        List<ParsedFollowUp> nodes = new ArrayList<>();

        String pendingKey = null;
        String pendingQuestion = null;
        int pendingHeadingLevel = 0;
        StringBuilder answer = new StringBuilder();

        for (String line : lines) {
            Matcher matcher = FOLLOW_UP_HEADING.matcher(line);
            if (matcher.matches()) {
                if (pendingKey != null) {
                    nodes.add(buildFollowUp(
                            pendingKey,
                            pendingHeadingLevel,
                            pendingQuestion,
                            answer.toString(),
                            sourcePath
                    ));
                }

                pendingHeadingLevel = matcher.group(1)
                        .length();
                pendingKey = matcher.group(2);
                pendingQuestion = matcher.group(3)
                        .trim();
                answer.setLength(0);
                continue;
            }

            answer.append(line)
                    .append('\n');
        }

        if (pendingKey != null) {
            nodes.add(buildFollowUp(
                    pendingKey,
                    pendingHeadingLevel,
                    pendingQuestion,
                    answer.toString(),
                    sourcePath
            ));
        }

        if (nodes.isEmpty()) {
            throw new CardParseException(
                    sourcePath,
                    "「## " + SECTION_FOLLOW_UP + "」章节没有任何 ### Q1: 形式的追问节点"
            );
        }

        validateFollowUpTree(nodes, sourcePath);

        return nodes;
    }

    private ParsedFollowUp buildFollowUp(
            String qKey,
            int headingLevel,
            String question,
            String answerMd,
            String sourcePath
    ) {
        int depth = depthOf(qKey);
        int expectedHeadingLevel = depth + 2;

        if (headingLevel != expectedHeadingLevel) {
            throw new CardParseException(
                    sourcePath,
                    "追问「" + qKey + "」的标题层级与编号不符：编号有 " + depth + " 段，"
                            + "应使用 " + "#".repeat(expectedHeadingLevel) + "，实际是 "
                            + "#".repeat(headingLevel)
            );
        }

        if (question.isBlank()) {
            throw new CardParseException(sourcePath, "追问「" + qKey + "」的问题内容为空");
        }

        if (answerMd.isBlank()) {
            throw new CardParseException(sourcePath, "追问「" + qKey + "」缺少参考答案");
        }

        return new ParsedFollowUp(
                qKey,
                parentKeyOf(qKey),
                question,
                answerMd.strip(),
                depth
        );
    }

    /**
     * 校验追问链是一棵连通的树：编号不能重复，父编号必须存在，
     * 否则导入后会出现挂不上父节点的孤儿追问。
     */
    private void validateFollowUpTree(List<ParsedFollowUp> nodes, String sourcePath) {
        Set<String> keys = new LinkedHashSet<>();

        for (ParsedFollowUp node : nodes) {
            if (!keys.add(node.qKey())) {
                throw new CardParseException(sourcePath, "追问编号「" + node.qKey() + "」重复");
            }
        }

        for (ParsedFollowUp node : nodes) {
            String parentKey = node.parentQKey();
            if (parentKey != null && !keys.contains(parentKey)) {
                throw new CardParseException(
                        sourcePath,
                        "追问「" + node.qKey() + "」的父编号「" + parentKey + "」不存在，追问链断了"
                );
            }
        }
    }

    private static int depthOf(String qKey) {
        int depth = 1;
        for (int i = 0; i < qKey.length(); i++) {
            if (qKey.charAt(i) == '.') {
                depth++;
            }
        }

        return depth;
    }

    private static String parentKeyOf(String qKey) {
        int lastDot = qKey.lastIndexOf('.');

        return lastDot < 0 ? null : qKey.substring(0, lastDot);
    }

    // ------------------------------------------------------------------
    // 自测题
    // ------------------------------------------------------------------

    private List<ParsedQuestion> parseQuestions(List<String> lines, String cardSlug, String sourcePath) {
        String yamlBlock = extractYamlBlock(lines, sourcePath);
        Map<String, Object> root = loadYamlMap(yamlBlock, sourcePath, "自测题");

        Object rawQuestions = root.get("questions");
        if (!(rawQuestions instanceof List<?> questionList) || questionList.isEmpty()) {
            throw new CardParseException(sourcePath, "自测题的 YAML 中 questions 必须是非空列表");
        }

        List<ParsedQuestion> questions = new ArrayList<>();
        for (int i = 0; i < questionList.size(); i++) {
            Object item = questionList.get(i);
            if (!(item instanceof Map<?, ?> rawMap)) {
                throw new CardParseException(sourcePath, "第 " + (i + 1) + " 道题不是 YAML 映射");
            }

            String qKey = cardSlug + "-" + (i + 1);
            questions.add(buildQuestion(stringKeyMap(rawMap), qKey, sourcePath));
        }

        return questions;
    }

    private String extractYamlBlock(List<String> lines, String sourcePath) {
        StringBuilder block = new StringBuilder();
        int fenceLength = 0;

        for (String line : lines) {
            if (fenceLength == 0) {
                Matcher opener = YAML_FENCE_OPEN.matcher(line);
                if (opener.matches()) {
                    fenceLength = opener.group(1)
                            .length();
                }
                continue;
            }

            if (FenceTracker.isClosingFence(line, fenceLength)) {
                fenceLength = 0;
                break;
            }

            block.append(line)
                    .append('\n');
        }

        if (block.isEmpty()) {
            throw new CardParseException(
                    sourcePath,
                    "「## " + SECTION_QUIZ + "」章节缺少 ```yaml 代码块"
                            + "（题干内含代码块时，请用四个反引号包裹 YAML）"
            );
        }

        return block.toString();
    }

    private ParsedQuestion buildQuestion(Map<String, Object> raw, String qKey, String sourcePath) {
        String type = readOptionalText(raw.get("type"))
                .toUpperCase(Locale.ROOT);
        if (!ALLOWED_QUESTION_TYPES.contains(type)) {
            throw new CardParseException(
                    sourcePath,
                    qKey + " 的 type 不合法：期望 " + ALLOWED_QUESTION_TYPES + "，实际「" + type + "」"
            );
        }

        String stem = readOptionalText(raw.get("stem"));
        if (stem.isBlank()) {
            throw new CardParseException(sourcePath, qKey + " 缺少 stem");
        }

        String analysis = readOptionalText(raw.get("analysis"));
        if (analysis.isBlank()) {
            throw new CardParseException(sourcePath, qKey + " 缺少 analysis（解析是学习价值所在，必填）");
        }

        int difficulty = readBoundedInt(raw.get("difficulty"), 2, 1, 3, sourcePath, qKey + " 的 difficulty");

        Map<String, String> options = readOptions(raw.get("options"), qKey, sourcePath);
        String answer = readOptionalText(raw.get("answer"))
                .toUpperCase(Locale.ROOT);
        List<List<String>> blanks = readBlanks(raw.get("blanks"), qKey, sourcePath);

        switch (type) {
            case "CHOICE", "MULTI" -> validateChoiceQuestion(
                    type, qKey, options, answer, sourcePath
            );
            case "JUDGE" -> validateJudgeAnswer(qKey, answer, sourcePath);
            case "CLOZE" -> validateClozeQuestion(qKey, stem, blanks, sourcePath);
            default -> throw new CardParseException(sourcePath, qKey + " 的 type 无法处理：" + type);
        }

        return new ParsedQuestion(
                qKey,
                type,
                stem,
                options.isEmpty() ? null : options,
                "CLOZE".equals(type) ? null : answer,
                blanks.isEmpty() ? null : blanks,
                analysis,
                difficulty
        );
    }

    private void validateChoiceQuestion(
            String type,
            String qKey,
            Map<String, String> options,
            String answer,
            String sourcePath
    ) {
        if (options.size() < 2) {
            throw new CardParseException(sourcePath, qKey + " 至少需要 2 个选项");
        }

        if (answer.isBlank()) {
            throw new CardParseException(sourcePath, qKey + " 缺少 answer");
        }

        if ("CHOICE".equals(type) && answer.length() != 1) {
            throw new CardParseException(sourcePath, qKey + " 是单选题，answer 只能是单个选项字母");
        }

        if ("MULTI".equals(type) && answer.length() < 2) {
            throw new CardParseException(sourcePath, qKey + " 是多选题，answer 应包含至少两个选项字母");
        }

        if (new LinkedHashSet<>(answer.chars()
                .mapToObj(c -> String.valueOf((char) c))
                .toList()).size() != answer.length()) {
            throw new CardParseException(sourcePath, qKey + " 的 answer「" + answer + "」有重复字母");
        }

        for (char letter : answer.toCharArray()) {
            if (!options.containsKey(String.valueOf(letter))) {
                throw new CardParseException(
                        sourcePath,
                        qKey + " 的 answer 含不存在的选项「" + letter + "」，可用选项：" + options.keySet()
                );
            }
        }
    }

    private void validateJudgeAnswer(String qKey, String answer, String sourcePath) {
        if (!"T".equals(answer) && !"F".equals(answer)) {
            throw new CardParseException(
                    sourcePath,
                    qKey + " 是判断题，answer 只能是 T 或 F，实际「" + answer + "」"
            );
        }
    }

    private void validateClozeQuestion(
            String qKey,
            String stem,
            List<List<String>> blanks,
            String sourcePath
    ) {
        if (blanks.isEmpty()) {
            throw new CardParseException(sourcePath, qKey + " 是挖空题，blanks 不能为空");
        }

        Set<Integer> placeholders = new LinkedHashSet<>();
        Matcher matcher = CLOZE_PLACEHOLDER.matcher(stem);
        while (matcher.find()) {
            placeholders.add(Integer.valueOf(matcher.group(1)));
        }

        if (placeholders.size() != blanks.size()) {
            throw new CardParseException(
                    sourcePath,
                    qKey + " 的占位符个数（" + placeholders.size() + "）与 blanks 个数（"
                            + blanks.size() + "）不一致"
            );
        }

        for (int i = 0; i < blanks.size(); i++) {
            if (placeholders.contains(i + 1)) {
                continue;
            }
            throw new CardParseException(
                    sourcePath,
                    qKey + " 缺少 {{" + (i + 1) + "}} 占位符（占位符必须从 1 开始连续编号）"
            );
        }

        for (List<String> accepted : blanks) {
            if (accepted.isEmpty()) {
                throw new CardParseException(sourcePath, qKey + " 的 blanks 中存在空的可接受答案列表");
            }
        }
    }

    private Map<String, String> readOptions(Object raw, String qKey, String sourcePath) {
        if (raw == null) {
            return Map.of();
        }

        if (!(raw instanceof Map<?, ?> map)) {
            throw new CardParseException(sourcePath, qKey + " 的 options 必须是映射（A: xxx）");
        }

        Map<String, String> options = new LinkedHashMap<>();
        map.forEach((key, value) -> options.put(
                stringify(key)
                        .toUpperCase(Locale.ROOT),
                stringify(value)
        ));

        return options;
    }

    private List<List<String>> readBlanks(Object raw, String qKey, String sourcePath) {
        if (raw == null) {
            return List.of();
        }

        if (!(raw instanceof List<?> outer)) {
            throw new CardParseException(sourcePath, qKey + " 的 blanks 必须是数组的数组");
        }

        List<List<String>> blanks = new ArrayList<>();
        for (Object item : outer) {
            // 允许写单个字符串，等价于只有一个可接受答案
            if (item instanceof List<?> inner) {
                List<String> accepted = new ArrayList<>();
                inner.forEach(value -> accepted.add(stringify(value)));
                blanks.add(accepted);
                continue;
            }

            blanks.add(List.of(stringify(item)));
        }

        return blanks;
    }

    // ------------------------------------------------------------------
    // 通用取值与校验
    // ------------------------------------------------------------------

    private List<ParsedRelation> readRelations(Object raw, String sourcePath) {
        if (raw == null) {
            return List.of();
        }

        if (!(raw instanceof List<?> list)) {
            throw new CardParseException(sourcePath, "frontmatter 的 related 必须是列表");
        }

        List<ParsedRelation> relations = new ArrayList<>();
        for (Object item : list) {
            // 允许简写：related: [some-slug]
            if (!(item instanceof Map<?, ?> map)) {
                String target = stringify(item);
                if (target.isBlank()) {
                    throw new CardParseException(sourcePath, "related 中存在空的 slug");
                }
                relations.add(new ParsedRelation(target, ParsedRelation.DEFAULT_TYPE));
                continue;
            }

            Map<String, Object> entry = stringKeyMap(map);
            String target = readOptionalText(entry.get("slug"));
            if (target.isBlank()) {
                throw new CardParseException(sourcePath, "related 的每一项都必须有 slug");
            }

            String type = readOptionalText(entry.get("type"))
                    .toUpperCase(Locale.ROOT);
            if (type.isBlank()) {
                type = ParsedRelation.DEFAULT_TYPE;
            }

            if (!ParsedRelation.ALLOWED_TYPES.contains(type)) {
                throw new CardParseException(
                        sourcePath,
                        "related 的 type 不合法：" + type + "，允许值 " + ParsedRelation.ALLOWED_TYPES
                );
            }

            relations.add(new ParsedRelation(target, type));
        }

        return relations;
    }

    private List<String> readStringList(Object raw, String sourcePath, String fieldName) {
        if (raw == null) {
            throw new CardParseException(sourcePath, "frontmatter 缺少 " + fieldName);
        }

        List<String> values = new ArrayList<>();

        if (raw instanceof List<?> list) {
            list.forEach(item -> values.add(stringify(item)));
        } else {
            // 允许 tags: HashMap,集合 这种逗号分隔写法
            for (String piece : stringify(raw).split(",")) {
                values.add(piece);
            }
        }

        values.replaceAll(String::strip);
        values.removeIf(value -> value.isEmpty());

        if (values.isEmpty()) {
            throw new CardParseException(sourcePath, "frontmatter 的 " + fieldName + " 不能为空");
        }

        return values;
    }

    private int readBoundedInt(
            Object raw,
            int defaultValue,
            int min,
            int max,
            String sourcePath,
            String fieldName
    ) {
        if (raw == null) {
            return defaultValue;
        }

        int value;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else {
            try {
                value = Integer.parseInt(stringify(raw).strip());
            } catch (NumberFormatException e) {
                throw new CardParseException(sourcePath, fieldName + " 必须是整数，实际「" + raw + "」");
            }
        }

        if (value < min || value > max) {
            throw new CardParseException(
                    sourcePath,
                    fieldName + " 超出范围 " + min + "~" + max + "，实际 " + value
            );
        }

        return value;
    }

    private String requireText(
            Map<String, Object> meta,
            String key,
            String sourcePath,
            String what
    ) {
        String value = readOptionalText(meta.get(key));
        if (value.isBlank()) {
            throw new CardParseException(sourcePath, what + " 缺少必填字段 " + key);
        }

        return value;
    }

    /**
     * YAML 会把 {@code 0.5} 解析成 Double、{@code true} 解析成 Boolean，
     * 而选项文本和挖空答案都应该是字符串，这里统一归一化。
     */
    private String stringify(Object value) {
        if (value == null) {
            return "";
        }

        if (value instanceof Double doubleValue && doubleValue == Math.floor(doubleValue)
                && !doubleValue.isInfinite()) {
            return String.valueOf(doubleValue.longValue());
        }

        return String.valueOf(value);
    }

    private String readOptionalText(Object value) {
        return value == null ? "" : stringify(value)
                .strip();
    }

    private Map<String, Object> stringKeyMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(stringify(key), value));

        return result;
    }

    private Map<String, Object> loadYamlMap(String yamlText, String sourcePath, String what) {
        Object loaded;
        try {
            loaded = yaml.load(yamlText);
        } catch (RuntimeException e) {
            throw new CardParseException(sourcePath, what + " 的 YAML 语法错误：" + e.getMessage());
        }

        if (loaded == null) {
            return Map.of();
        }

        if (!(loaded instanceof Map<?, ?> map)) {
            throw new CardParseException(sourcePath, what + " 必须是 YAML 映射");
        }

        return stringKeyMap(map);
    }

    private void validateSlug(String slug, String sourcePath) {
        if (!slug.matches("^[a-z0-9]+(?:-[a-z0-9]+)*$")) {
            throw new CardParseException(
                    sourcePath,
                    "slug「" + slug + "」不合法：只能是小写字母、数字和连字符，如 hashmap-internals"
            );
        }
    }

    private record FrontMatterSplit(Map<String, Object> meta, String body) {
    }

    /**
     * 围栏代码块状态机。
     *
     * <p>按 CommonMark 的规则判断闭合：闭合围栏必须与起始围栏同种字符、且不短于它。
     * 这样用四个反引号包裹的块内可以安全地出现 ``` 代码块——挖空题的题干正是这种结构，
     * 若用简单开关翻转，内层 ``` 会把它提前闭合，章节从此错位。
     */
    private static final class FenceTracker {

        private char fenceChar = 0;
        private int fenceLength = 0;

        /**
         * @return 当前行是否为围栏标记行（起始或闭合）
         */
        boolean accept(String line) {
            if (fenceChar == 0) {
                Matcher opener = FENCE_OPEN.matcher(line);
                if (!opener.find()) {
                    return false;
                }

                String fence = opener.group(1);
                fenceChar = fence.charAt(0);
                fenceLength = fence.length();

                return true;
            }

            if (!isClosingFence(line, fenceLength)) {
                return false;
            }

            fenceChar = 0;
            fenceLength = 0;

            return true;
        }

        boolean inside() {
            return fenceChar != 0;
        }

        static boolean isClosingFence(String line, int minLength) {
            String trimmed = line.strip();
            if (trimmed.length() < minLength) {
                return false;
            }

            char first = trimmed.charAt(0);
            if (first != '`' && first != '~') {
                return false;
            }

            for (int i = 0; i < trimmed.length(); i++) {
                if (trimmed.charAt(i) != first) {
                    return false;
                }
            }

            return true;
        }
    }
}
