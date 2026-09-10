package com.jis.quiz.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jis.common.BizException;
import com.jis.common.ResultCode;
import com.jis.content.entity.ContentModule;
import com.jis.content.entity.KnowledgePoint;
import com.jis.content.mapper.ContentModuleMapper;
import com.jis.content.mapper.KnowledgePointMapper;
import com.jis.content.service.ContentQueryService;
import com.jis.quiz.dto.QuizAnswer;
import com.jis.quiz.dto.QuizOption;
import com.jis.quiz.dto.QuizQuestionVO;
import com.jis.quiz.dto.QuizResultVO;
import com.jis.quiz.dto.QuizSubmitRequest;
import com.jis.quiz.dto.QuizSubmitResultVO;
import com.jis.quiz.dto.WrongQuestionRow;
import com.jis.quiz.entity.QuizQuestion;
import com.jis.quiz.entity.QuizRecord;
import com.jis.quiz.mapper.QuizQuestionMapper;
import com.jis.quiz.mapper.QuizRecordMapper;
import com.jis.stats.mapper.StudyDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 自测答题。抽题、判分、错题本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizService {

    private static final int MAX_DRAW_COUNT = 50;
    private static final int DEFAULT_DRAW_COUNT = 10;
    private static final int MAX_WRONG_BOOK_SIZE = 200;

    private static final Map<String, String> TYPE_LABELS = Map.of(
            QuizQuestion.TYPE_CHOICE, "单选",
            QuizQuestion.TYPE_MULTI, "多选",
            QuizQuestion.TYPE_JUDGE, "判断",
            QuizQuestion.TYPE_CLOZE, "代码挖空"
    );

    private final QuizQuestionMapper quizQuestionMapper;
    private final QuizRecordMapper quizRecordMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final ContentModuleMapper contentModuleMapper;
    private final ContentQueryService contentQueryService;
    private final StudyDailyMapper studyDailyMapper;
    private final ObjectMapper objectMapper;
    private final QuizGrader quizGrader;

    /**
     * 随机抽题。
     *
     * @param moduleSlug 限定模块（含子模块）；null 表示全部
     * @param type       限定题型；null 表示混合
     * @param count      抽题数量
     */
    public List<QuizQuestionVO> draw(Long userId, String moduleSlug, String type, Integer count) {
        Set<Long> moduleIds = resolveModuleIds(moduleSlug);
        int wanted = count == null
                ? DEFAULT_DRAW_COUNT
                : Math.min(Math.max(count, 1), MAX_DRAW_COUNT);

        String normalizedType = normalizeType(type);

        List<QuizQuestion> questions = quizQuestionMapper.selectRandom(moduleIds, normalizedType, wanted);

        return toVos(questions, false);
    }

    /**
     * 批量判分。
     *
     * <p>整批放在一个事务里：要么全部记录成功，要么全部不记，
     * 避免网络中断留下一批「答了但没记录」的题目影响后续统计。
     */
    @Transactional
    public QuizSubmitResultVO submit(Long userId, QuizSubmitRequest request) {
        // 同一 qKey 重复提交时只取第一次，否则正确率统计会被重复计算
        Map<String, QuizAnswer> answerByKey = new LinkedHashMap<>();
        for (QuizAnswer answer : request.answers()) {
            answerByKey.putIfAbsent(answer.qKey(), answer);
        }

        Map<String, QuizQuestion> questionByKey = new LinkedHashMap<>();
        quizQuestionMapper.selectByQKeys(answerByKey.keySet())
                .forEach(question -> questionByKey.put(question.getQKey(), question));

        List<QuizResultVO> results = new ArrayList<>();
        List<QuizRecord> records = new ArrayList<>();
        int correctCount = 0;

        for (QuizAnswer answer : answerByKey.values()) {
            QuizQuestion question = questionByKey.get(answer.qKey());
            if (question == null) {
                throw new BizException(
                        ResultCode.QUESTION_NOT_FOUND,
                        "题目不存在：" + answer.qKey()
                );
            }

            QuizGrader.Graded graded = quizGrader.grade(question, answer);
            if (graded.correct()) {
                correctCount++;
            }

            results.add(new QuizResultVO(
                    question.getQKey(),
                    question.getType(),
                    graded.correct(),
                    graded.userAnswer(),
                    QuizQuestion.TYPE_CLOZE.equals(question.getType()) ? null : question.getAnswer(),
                    graded.blankResults(),
                    graded.acceptedBlanks(),
                    question.getAnalysisMd()
            ));

            QuizRecord record = new QuizRecord();
            record.setUserId(userId);
            record.setQuestionId(question.getId());
            record.setUserAnswer(graded.userAnswer() == null ? "" : graded.userAnswer());
            record.setIsCorrect(graded.correct() ? 1 : 0);
            record.setAnsweredAt(LocalDateTime.now()
                    .withNano(0));
            records.add(record);
        }

        records.forEach(quizRecordMapper::insert);

        studyDailyMapper.accumulateQuiz(
                userId,
                LocalDate.now(),
                results.size(),
                correctCount,
                request.durationSecOrDefault()
        );

        return new QuizSubmitResultVO(
                results.size(),
                correctCount,
                results.isEmpty() ? 0 : (int) Math.round(correctCount * 100d / results.size()),
                results
        );
    }

    /**
     * 错题本：最近一次作答错误的题目，附带解析便于直接复习。
     */
    public List<QuizQuestionVO> wrongBook(Long userId) {
        List<WrongQuestionRow> rows = quizRecordMapper.selectWrongQuestions(userId, MAX_WRONG_BOOK_SIZE);
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> questionIds = rows.stream()
                .map(WrongQuestionRow::getQuestionId)
                .toList();

        Map<Long, QuizQuestion> questionById = new LinkedHashMap<>();
        quizQuestionMapper.selectByIds(questionIds)
                .forEach(question -> questionById.put(question.getId(), question));

        // 保持按最近答错时间排序，跳过已被内容导入删除的题目
        List<QuizQuestion> ordered = new ArrayList<>();
        for (Long questionId : questionIds) {
            QuizQuestion question = questionById.get(questionId);
            if (question != null) {
                ordered.add(question);
            }
        }

        return toVos(ordered, true);
    }

    public long countWrong(Long userId) {
        return quizRecordMapper.countWrongQuestions(userId);
    }

    // ------------------------------------------------------------------
    // 组装
    // ------------------------------------------------------------------

    /**
     * @param withDetail 是否带上答案与解析。抽题时必须为 false，
     *                   否则前端一拿到题干就同时拿到了答案。
     */
    private List<QuizQuestionVO> toVos(List<QuizQuestion> questions, boolean withDetail) {
        if (questions.isEmpty()) {
            return List.of();
        }

        Map<Long, KnowledgePoint> kpById = new LinkedHashMap<>();
        Set<Long> kpIds = new LinkedHashSet<>();
        questions.forEach(question -> {
            if (question.getKpId() != null) {
                kpIds.add(question.getKpId());
            }
        });

        if (!kpIds.isEmpty()) {
            knowledgePointMapper.selectByIds(kpIds)
                    .forEach(kp -> kpById.put(kp.getId(), kp));
        }

        Map<Long, String> moduleNames = loadModuleNames(kpById.values());

        List<QuizQuestionVO> result = new ArrayList<>();
        for (QuizQuestion question : questions) {
            KnowledgePoint kp = question.getKpId() == null ? null : kpById.get(question.getKpId());
            List<List<String>> blanks = quizGrader.readBlanks(question.getBlanksJson());

            result.add(new QuizQuestionVO(
                    question.getQKey(),
                    question.getType(),
                    TYPE_LABELS.getOrDefault(question.getType(), question.getType()),
                    question.getStemMd(),
                    readOptions(question.getOptionsJson()),
                    blanks.isEmpty() ? null : blanks.size(),
                    question.getDifficulty() == null ? 2 : question.getDifficulty(),
                    kp == null ? null : kp.getSlug(),
                    kp == null ? null : kp.getTitle(),
                    kp == null ? null : moduleNames.get(kp.getModuleId()),
                    withDetail ? question.getAnswer() : null,
                    withDetail && !blanks.isEmpty() ? blanks : null,
                    withDetail ? question.getAnalysisMd() : null
            ));
        }

        return result;
    }

    /**
     * options_json 存的是 {@code {"A":"...","B":"..."}}，
     * 按选项字母排序后转成前端更好渲染的数组。
     */
    private List<QuizOption> readOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return null;
        }

        Map<String, String> raw = readJson(
                optionsJson,
                new TypeReference<LinkedHashMap<String, String>>() {
                }
        );
        if (raw == null || raw.isEmpty()) {
            return null;
        }

        return raw.entrySet()
                .stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> new QuizOption(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<List<String>> readBlanks(String blanksJson) {
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
            // 记下来但不让整批抽题失败
            log.warn("题目 JSON 字段解析失败，按空值处理: {}", e.getMessage());

            return null;
        }
    }

    private Map<Long, String> loadModuleNames(Collection<KnowledgePoint> points) {
        Set<Long> moduleIds = new LinkedHashSet<>();
        points.forEach(kp -> {
            if (kp.getModuleId() != null) {
                moduleIds.add(kp.getModuleId());
            }
        });

        if (moduleIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> names = new LinkedHashMap<>();
        contentModuleMapper.selectByIds(moduleIds)
                .forEach(module -> names.put(module.getId(), module.getName()));

        return names;
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }

        String normalized = type.strip()
                .toUpperCase(Locale.ROOT);
        if (!TYPE_LABELS.containsKey(normalized)) {
            throw new BizException(
                    ResultCode.BAD_REQUEST,
                    "题型不合法：" + type + "，可选值 " + TYPE_LABELS.keySet()
            );
        }

        return normalized;
    }

    private Set<Long> resolveModuleIds(String moduleSlug) {
        Set<Long> moduleIds = contentQueryService.resolveModuleIds(moduleSlug);
        if (moduleIds != null && moduleIds.isEmpty()) {
            throw new BizException(ResultCode.MODULE_NOT_FOUND);
        }

        return moduleIds;
    }
}
