package com.jis.quiz.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jis.quiz.dto.QuizAnswer;
import com.jis.quiz.entity.QuizQuestion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 自测题判分。
 *
 * <p>从 {@code QuizService} 里独立出来，是因为判分是纯计算，却最容易在细节上出错：
 * 多选必须按集合比较（作答顺序不该影响对错）、挖空题要容忍空白的写法差异，
 * 但又不能宽松到把「a b」和「ab」当成同一个答案。这些分支原先埋在 Service 的
 * 私有方法里，没有 Spring 上下文就测不到——错了也不会报错，只是判分结果不对。
 * 抽成不依赖容器的类之后，判分规则可以被直接覆盖。
 *
 * <p>这里的 ObjectMapper 是内部自建的，没有注册成 Bean。一旦在配置类里定义
 * ObjectMapper，Spring Boot 的 Jackson 自动配置就会退让，从而影响整个 Web 层的
 * JSON 行为；缓存层出于同样的理由也是自建。
 */
@Slf4j
@Component
public class QuizGrader {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 给一道题判分。
     *
     * @param question 题目，判分只读 type / answer / blanksJson
     * @param answer   用户作答
     */
    public Graded grade(QuizQuestion question, QuizAnswer answer) {
        if (QuizQuestion.TYPE_CLOZE.equals(question.getType())) {
            return gradeCloze(question, answer);
        }

        return gradeChoiceLike(question, answer);
    }

    private Graded gradeChoiceLike(QuizQuestion question, QuizAnswer answer) {
        String expected = normalizeAnswer(question.getAnswer());
        String actual = normalizeAnswer(answer.answer());

        boolean correct;
        if (QuizQuestion.TYPE_MULTI.equals(question.getType())) {
            // 多选按集合比较，作答顺序不影响对错
            correct = letterSetOf(expected).equals(letterSetOf(actual));
        } else {
            correct = expected.equals(actual);
        }

        // 多选题把用户作答规范化成字母升序，便于回显与入库后比对
        String normalizedUserAnswer = QuizQuestion.TYPE_MULTI.equals(question.getType())
                ? joinLetters(letterSetOf(actual))
                : actual;

        return new Graded(correct, normalizedUserAnswer, null, null);
    }

    private Graded gradeCloze(QuizQuestion question, QuizAnswer answer) {
        List<List<String>> acceptedBlanks = readBlanks(question.getBlanksJson());
        List<String> submitted = answer.blanks() == null ? List.of() : answer.blanks();

        List<Boolean> blankResults = new ArrayList<>();
        List<String> normalizedSubmitted = new ArrayList<>();

        for (int index = 0; index < acceptedBlanks.size(); index++) {
            String value = index < submitted.size() ? submitted.get(index) : null;
            normalizedSubmitted.add(value == null ? "" : value.strip());
            blankResults.add(matchesAny(value, acceptedBlanks.get(index)));
        }

        boolean correct = !blankResults.isEmpty()
                && blankResults.stream()
                .allMatch(Boolean::booleanValue);

        return new Graded(
                correct,
                String.join(" | ", normalizedSubmitted),
                blankResults,
                acceptedBlanks
        );
    }

    /**
     * 挖空题的答案比对：忽略大小写、首尾空白，并把连续空白折叠成一个空格。
     *
     * <p>写代码时多敲一个空格不该算错，但也不能宽松到把空白全部删掉——
     * 「a b」和「ab」在代码里是两个不同的东西。
     */
    private boolean matchesAny(String submitted, List<String> accepted) {
        String normalized = normalizeBlank(submitted);
        if (normalized.isEmpty()) {
            return false;
        }

        return accepted.stream()
                .anyMatch(candidate -> normalizeBlank(candidate).equals(normalized));
    }

    private String normalizeBlank(String value) {
        return value == null
                ? ""
                : value.strip()
                        .replaceAll("\\s+", " ")
                        .toLowerCase(Locale.ROOT);
    }

    private String normalizeAnswer(String value) {
        return value == null ? "" : value.strip()
                .toUpperCase(Locale.ROOT);
    }

    private Set<Character> letterSetOf(String value) {
        Set<Character> letters = new TreeSet<>();
        for (char character : value.toCharArray()) {
            if (Character.isLetterOrDigit(character)) {
                letters.add(character);
            }
        }

        return letters;
    }

    private String joinLetters(Set<Character> letters) {
        StringBuilder builder = new StringBuilder();
        letters.forEach(builder::append);

        return builder.toString();
    }

    /**
     * 解析题目里存的可接受答案。也供 VO 组装复用，所以是 public。
     */
    public List<List<String>> readBlanks(String blanksJson) {
        if (blanksJson == null || blanksJson.isBlank()) {
            return List.of();
        }

        List<List<String>> blanks = readJson(
                blanksJson,
                new TypeReference<List<List<String>>>() {
                }
        );

        return blanks == null ? List.of() : blanks;
    }

    private <T> T readJson(String payload, TypeReference<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (Exception e) {
            // 题目的 JSON 字段由导入器生成，解析失败说明数据被外部改坏了，
            // 记下来但不让整批发卷失败
            log.warn("题目 JSON 字段解析失败，按空值处理: {}", e.getMessage());

            return null;
        }
    }

    /**
     * 单题判分结果。
     *
     * @param userAnswer     规范化后的用户作答
     * @param blankResults   挖空题各空对错；非挖空题为 null
     * @param acceptedBlanks 挖空题各空可接受答案；非挖空题为 null
     */
    public record Graded(

            boolean correct,

            String userAnswer,

            List<Boolean> blankResults,

            List<List<String>> acceptedBlanks
    ) {
    }
}
