package com.jis.backup.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jis.backup.dto.BackupSummaryVO;
import com.jis.backup.dto.BackupVO;
import com.jis.backup.dto.FavoriteItem;
import com.jis.backup.dto.NoteItem;
import com.jis.backup.dto.QuizRecordItem;
import com.jis.backup.dto.ReviewStateItem;
import com.jis.backup.dto.StudyDailyItem;
import com.jis.common.BizException;
import com.jis.common.ResultCode;
import com.jis.content.entity.KnowledgePoint;
import com.jis.content.entity.UserFavorite;
import com.jis.content.entity.UserNote;
import com.jis.content.mapper.KnowledgePointMapper;
import com.jis.content.mapper.UserFavoriteMapper;
import com.jis.content.mapper.UserNoteMapper;
import com.jis.quiz.entity.QuizRecord;
import com.jis.quiz.mapper.QuizQuestionMapper;
import com.jis.quiz.mapper.QuizRecordMapper;
import com.jis.review.entity.ReviewState;
import com.jis.review.mapper.ReviewStateMapper;
import com.jis.stats.entity.StudyDaily;
import com.jis.stats.mapper.StudyDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 学习数据的导出与恢复。
 *
 * <p>导出范围是「用户行为」：复习状态、笔记、收藏、答题记录、每日统计。
 * 刻意<b>不导出</b> {@code review_log}：它是只追加的流水，体积随复习次数线性增长，
 * 而恢复时真正需要的是每张卡最新的 FSRS 状态（{@code review_state}）——
 * 流水只用于统计与算法调参，重建后从恢复时点重新累积即可。
 *
 * <p>关联一律以业务键（卡片 slug、题目 qKey）进出，服务内部再换算成主键。
 * 这样备份文件不含自增 id，也不会因换库自增序列不同而错位。
 *
 * <p>导入只写当前 {@code userId} 名下的行，按各自唯一键 upsert，因此同一个文件
 * 重复导入两次结果一致；目标卡片/题目已不存在时跳过并记入警告，不让整单失败
 * （内容随迭代增删，旧备份里出现已删除的卡片是正常情况）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupService {

    /**
     * 警告过多时只保留前若干条，避免响应体被旧备份里成百上千条失效卡片撑爆。
     */
    private static final int MAX_WARNINGS = 100;

    private final ReviewStateMapper reviewStateMapper;
    private final UserNoteMapper userNoteMapper;
    private final UserFavoriteMapper userFavoriteMapper;
    private final QuizRecordMapper quizRecordMapper;
    private final StudyDailyMapper studyDailyMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final QuizQuestionMapper quizQuestionMapper;

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    public BackupVO export(Long userId) {
        return new BackupVO(
                BackupVO.CURRENT_VERSION,
                LocalDateTime.now()
                        .withNano(0),
                exportReviewStates(userId),
                exportNotes(userId),
                exportFavorites(userId),
                exportQuizRecords(userId),
                exportStudyDaily(userId)
        );
    }

    private List<ReviewStateItem> exportReviewStates(Long userId) {
        List<ReviewState> states = reviewStateMapper.selectList(
                Wrappers.<ReviewState>lambdaQuery()
                        .eq(ReviewState::getUserId, userId)
                        .orderByAsc(ReviewState::getId)
        );

        Map<Long, String> slugById = loadSlugs(
                states.stream()
                        .map(ReviewState::getKpId)
                        .toList()
        );

        List<ReviewStateItem> items = new ArrayList<>();
        for (ReviewState state : states) {
            String slug = slugById.get(state.getKpId());
            if (slug == null) {
                // 卡片已被内容迭代删除，业务键无从表达，这部分进度无法随备份带走
                continue;
            }

            items.add(new ReviewStateItem(
                    slug,
                    orDefault(state.getState(), ReviewState.STATE_NEW),
                    orDefault(state.getStability(), 0d),
                    orDefault(state.getDifficulty(), 0d),
                    orDefault(state.getReps(), 0),
                    orDefault(state.getLapses(), 0),
                    state.getDueAt(),
                    state.getLastReviewAt()
            ));
        }

        return items;
    }

    private List<NoteItem> exportNotes(Long userId) {
        List<UserNote> notes = userNoteMapper.selectList(
                Wrappers.<UserNote>lambdaQuery()
                        .eq(UserNote::getUserId, userId)
                        .orderByAsc(UserNote::getId)
        );

        Map<Long, String> slugById = loadSlugs(
                notes.stream()
                        .map(UserNote::getKpId)
                        .toList()
        );

        List<NoteItem> items = new ArrayList<>();
        for (UserNote note : notes) {
            String slug = slugById.get(note.getKpId());
            if (slug == null) {
                continue;
            }

            items.add(new NoteItem(
                    slug,
                    note.getContentMd() == null ? "" : note.getContentMd(),
                    note.getUpdateTime()
            ));
        }

        return items;
    }

    private List<FavoriteItem> exportFavorites(Long userId) {
        List<UserFavorite> favorites = userFavoriteMapper.selectList(
                Wrappers.<UserFavorite>lambdaQuery()
                        .eq(UserFavorite::getUserId, userId)
                        .orderByAsc(UserFavorite::getId)
        );

        Map<Long, String> slugById = loadSlugs(
                favorites.stream()
                        .map(UserFavorite::getKpId)
                        .toList()
        );

        List<FavoriteItem> items = new ArrayList<>();
        for (UserFavorite favorite : favorites) {
            String slug = slugById.get(favorite.getKpId());
            if (slug == null) {
                continue;
            }

            items.add(new FavoriteItem(slug, favorite.getCreateTime()));
        }

        return items;
    }

    private List<QuizRecordItem> exportQuizRecords(Long userId) {
        List<QuizRecord> records = quizRecordMapper.selectList(
                Wrappers.<QuizRecord>lambdaQuery()
                        .eq(QuizRecord::getUserId, userId)
                        .orderByAsc(QuizRecord::getId)
        );

        Map<Long, String> qKeyById = loadQKeys(
                records.stream()
                        .map(QuizRecord::getQuestionId)
                        .toList()
        );

        List<QuizRecordItem> items = new ArrayList<>();
        for (QuizRecord record : records) {
            String qKey = qKeyById.get(record.getQuestionId());
            if (qKey == null) {
                continue;
            }

            items.add(new QuizRecordItem(
                    qKey,
                    record.getUserAnswer() == null ? "" : record.getUserAnswer(),
                    record.getIsCorrect() != null && record.getIsCorrect() == 1,
                    record.getAnsweredAt()
            ));
        }

        return items;
    }

    private List<StudyDailyItem> exportStudyDaily(Long userId) {
        return studyDailyMapper.selectList(
                        Wrappers.<StudyDaily>lambdaQuery()
                                .eq(StudyDaily::getUserId, userId)
                                .orderByAsc(StudyDaily::getStatDate)
                )
                .stream()
                .filter(day -> day.getStatDate() != null)
                .map(day -> new StudyDailyItem(
                        day.getStatDate(),
                        orDefault(day.getReviewCount(), 0),
                        orDefault(day.getNewCount(), 0),
                        orDefault(day.getQuizCount(), 0),
                        orDefault(day.getCorrectCount(), 0),
                        orDefault(day.getDurationSec(), 0)
                ))
                .toList();
    }

    // ------------------------------------------------------------------
    // 导入
    // ------------------------------------------------------------------

    @Transactional
    public BackupSummaryVO importBackup(Long userId, BackupVO backup) {
        if (backup == null) {
            throw new BizException(ResultCode.BAD_REQUEST, "备份内容为空");
        }

        if (backup.version() != BackupVO.CURRENT_VERSION) {
            throw new BizException(
                    ResultCode.BAD_REQUEST,
                    "备份文件版本不受支持：" + backup.version()
            );
        }

        ImportContext context = new ImportContext(userId);

        int reviewStateCount = importReviewStates(context, backup.reviewStates());
        int noteCount = importNotes(context, backup.notes());
        int favoriteCount = importFavorites(context, backup.favorites());
        int quizRecordCount = importQuizRecords(context, backup.quizRecords());
        int studyDailyCount = importStudyDaily(context, backup.studyDaily());

        log.info(
                "备份导入完成 userId={} 复习 {} 笔记 {} 收藏 {} 答题 {} 统计 {} 跳过 {}",
                userId,
                reviewStateCount,
                noteCount,
                favoriteCount,
                quizRecordCount,
                studyDailyCount,
                context.skipped
        );

        return new BackupSummaryVO(
                reviewStateCount,
                noteCount,
                favoriteCount,
                quizRecordCount,
                studyDailyCount,
                context.skipped,
                List.copyOf(context.warnings)
        );
    }

    private int importReviewStates(ImportContext context, List<ReviewStateItem> rawItems) {
        List<ReviewStateItem> items = nullSafe(rawItems);
        Map<String, Long> idBySlug = loadKpIdsBySlug(collectSlugs(items, ReviewStateItem::cardSlug));

        int restored = 0;
        for (ReviewStateItem item : items) {
            if (item == null || isBlank(item.cardSlug())) {
                context.skip("有一条复习记录缺少 cardSlug，已跳过");
                continue;
            }

            Long kpId = idBySlug.get(item.cardSlug());
            if (kpId == null) {
                context.skip("卡片已不存在，跳过复习记录：" + item.cardSlug());
                continue;
            }

            // due_at 在库表中是 NOT NULL，缺了它没法构造一条合法状态
            if (item.dueAt() == null) {
                context.skip("复习记录缺少到期时间，已跳过：" + item.cardSlug());
                continue;
            }

            ReviewState state = reviewStateMapper.selectOne(
                    Wrappers.<ReviewState>lambdaQuery()
                            .eq(ReviewState::getUserId, context.userId)
                            .eq(ReviewState::getKpId, kpId)
            );

            boolean isNew = state == null;
            if (isNew) {
                state = new ReviewState();
                state.setUserId(context.userId);
                state.setKpId(kpId);
            }

            state.setState(item.state());
            state.setStability(item.stability());
            state.setDifficulty(item.difficulty());
            state.setReps(item.reps());
            state.setLapses(item.lapses());
            state.setDueAt(item.dueAt());
            state.setLastReviewAt(item.lastReviewAt());

            if (isNew) {
                reviewStateMapper.insert(state);
            } else {
                reviewStateMapper.updateById(state);
            }

            restored++;
        }

        return restored;
    }

    private int importNotes(ImportContext context, List<NoteItem> rawItems) {
        List<NoteItem> items = nullSafe(rawItems);
        Map<String, Long> idBySlug = loadKpIdsBySlug(collectSlugs(items, NoteItem::cardSlug));

        int restored = 0;
        for (NoteItem item : items) {
            if (item == null || isBlank(item.cardSlug())) {
                context.skip("有一条笔记缺少 cardSlug，已跳过");
                continue;
            }

            Long kpId = idBySlug.get(item.cardSlug());
            if (kpId == null) {
                context.skip("卡片已不存在，跳过笔记：" + item.cardSlug());
                continue;
            }

            String contentMd = item.contentMd() == null ? "" : item.contentMd();

            UserNote note = userNoteMapper.selectOne(
                    Wrappers.<UserNote>lambdaQuery()
                            .eq(UserNote::getUserId, context.userId)
                            .eq(UserNote::getKpId, kpId)
            );

            if (note == null) {
                note = new UserNote();
                note.setUserId(context.userId);
                note.setKpId(kpId);
                note.setContentMd(contentMd);
                if (item.updateTime() != null) {
                    note.setUpdateTime(item.updateTime());
                }
                userNoteMapper.insert(note);
            } else {
                note.setContentMd(contentMd);
                if (item.updateTime() != null) {
                    note.setUpdateTime(item.updateTime());
                }
                userNoteMapper.updateById(note);
            }

            restored++;
        }

        return restored;
    }

    private int importFavorites(ImportContext context, List<FavoriteItem> rawItems) {
        List<FavoriteItem> items = nullSafe(rawItems);
        Map<String, Long> idBySlug = loadKpIdsBySlug(collectSlugs(items, FavoriteItem::cardSlug));

        int restored = 0;
        for (FavoriteItem item : items) {
            if (item == null || isBlank(item.cardSlug())) {
                context.skip("有一条收藏缺少 cardSlug，已跳过");
                continue;
            }

            Long kpId = idBySlug.get(item.cardSlug());
            if (kpId == null) {
                context.skip("卡片已不存在，跳过收藏：" + item.cardSlug());
                continue;
            }

            boolean exists = userFavoriteMapper.exists(
                    Wrappers.<UserFavorite>lambdaQuery()
                            .eq(UserFavorite::getUserId, context.userId)
                            .eq(UserFavorite::getKpId, kpId)
            );

            if (!exists) {
                UserFavorite favorite = new UserFavorite();
                favorite.setUserId(context.userId);
                favorite.setKpId(kpId);
                if (item.createTime() != null) {
                    favorite.setCreateTime(item.createTime());
                }
                userFavoriteMapper.insert(favorite);
            }

            // 已收藏时保留库中原始 create_time，不因重复导入而改写收藏时间
            restored++;
        }

        return restored;
    }

    private int importQuizRecords(ImportContext context, List<QuizRecordItem> rawItems) {
        List<QuizRecordItem> items = nullSafe(rawItems);
        Map<String, Long> idByQKey = loadQuestionIdsByQKey(
                collectSlugs(items, QuizRecordItem::questionQKey)
        );

        int restored = 0;
        for (QuizRecordItem item : items) {
            if (item == null || isBlank(item.questionQKey())) {
                context.skip("有一条答题记录缺少 questionQKey，已跳过");
                continue;
            }

            Long questionId = idByQKey.get(item.questionQKey());
            if (questionId == null) {
                context.skip("题目已不存在，跳过答题记录：" + item.questionQKey());
                continue;
            }

            // answered_at 参与去重判定，缺了它无法保证「再导入一次不翻倍」
            if (item.answeredAt() == null) {
                context.skip("答题记录缺少作答时间，无法去重，已跳过：" + item.questionQKey());
                continue;
            }

            boolean exists = quizRecordMapper.exists(
                    Wrappers.<QuizRecord>lambdaQuery()
                            .eq(QuizRecord::getUserId, context.userId)
                            .eq(QuizRecord::getQuestionId, questionId)
                            .eq(QuizRecord::getAnsweredAt, item.answeredAt())
            );
            if (exists) {
                context.skip("答题记录已存在，跳过重复项：" + item.questionQKey());
                continue;
            }

            QuizRecord record = new QuizRecord();
            record.setUserId(context.userId);
            record.setQuestionId(questionId);
            record.setUserAnswer(item.userAnswer() == null ? "" : item.userAnswer());
            record.setIsCorrect(item.correct() ? 1 : 0);
            record.setAnsweredAt(item.answeredAt());
            quizRecordMapper.insert(record);

            restored++;
        }

        return restored;
    }

    private int importStudyDaily(ImportContext context, List<StudyDailyItem> rawItems) {
        List<StudyDailyItem> items = nullSafe(rawItems);

        int restored = 0;
        for (StudyDailyItem item : items) {
            if (item == null || item.statDate() == null) {
                context.skip("有一条每日统计缺少 statDate，已跳过");
                continue;
            }

            StudyDaily day = studyDailyMapper.selectOne(
                    Wrappers.<StudyDaily>lambdaQuery()
                            .eq(StudyDaily::getUserId, context.userId)
                            .eq(StudyDaily::getStatDate, item.statDate())
            );

            boolean isNew = day == null;
            if (isNew) {
                day = new StudyDaily();
                day.setUserId(context.userId);
                day.setStatDate(item.statDate());
            }

            // 覆盖为备份中的绝对值而非累加：重复导入同一份文件时数字必须不变
            day.setReviewCount(item.reviewCount());
            day.setNewCount(item.newCount());
            day.setQuizCount(item.quizCount());
            day.setCorrectCount(item.correctCount());
            day.setDurationSec(item.durationSec());

            if (isNew) {
                studyDailyMapper.insert(day);
            } else {
                studyDailyMapper.updateById(day);
            }

            restored++;
        }

        return restored;
    }

    // ------------------------------------------------------------------
    // 业务键换算与工具
    // ------------------------------------------------------------------

    private Map<Long, String> loadSlugs(Collection<Long> kpIds) {
        Set<Long> distinctIds = distinct(kpIds);
        if (distinctIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> slugById = new LinkedHashMap<>();
        knowledgePointMapper.selectByIds(distinctIds)
                .forEach(kp -> slugById.put(kp.getId(), kp.getSlug()));

        return slugById;
    }

    private Map<Long, String> loadQKeys(Collection<Long> questionIds) {
        Set<Long> distinctIds = distinct(questionIds);
        if (distinctIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> qKeyById = new LinkedHashMap<>();
        quizQuestionMapper.selectByIds(distinctIds)
                .forEach(question -> qKeyById.put(question.getId(), question.getQKey()));

        return qKeyById;
    }

    private Map<String, Long> loadKpIdsBySlug(Collection<String> slugs) {
        Set<String> distinctSlugs = distinctSlugs(slugs);
        if (distinctSlugs.isEmpty()) {
            return Map.of();
        }

        Map<String, Long> idBySlug = new LinkedHashMap<>();
        knowledgePointMapper.selectList(
                        Wrappers.<KnowledgePoint>lambdaQuery()
                                .select(KnowledgePoint::getId, KnowledgePoint::getSlug)
                                .in(KnowledgePoint::getSlug, distinctSlugs)
                )
                .forEach(kp -> idBySlug.put(kp.getSlug(), kp.getId()));

        return idBySlug;
    }

    private Map<String, Long> loadQuestionIdsByQKey(Collection<String> qKeys) {
        Set<String> distinctQKeys = distinctSlugs(qKeys);
        if (distinctQKeys.isEmpty()) {
            return Map.of();
        }

        Map<String, Long> idByQKey = new LinkedHashMap<>();
        quizQuestionMapper.selectByQKeys(distinctQKeys)
                .forEach(question -> idByQKey.put(question.getQKey(), question.getId()));

        return idByQKey;
    }

    private static <T> List<T> nullSafe(List<T> items) {
        return items == null ? List.of() : items;
    }

    private static <T> Set<Long> distinct(Collection<Long> ids) {
        Set<Long> distinctIds = new LinkedHashSet<>();
        if (ids != null) {
            ids.forEach(id -> {
                if (id != null) {
                    distinctIds.add(id);
                }
            });
        }

        return distinctIds;
    }

    private static Set<String> distinctSlugs(Collection<String> values) {
        Set<String> distinctValues = new LinkedHashSet<>();
        if (values != null) {
            values.forEach(value -> {
                if (!isBlank(value)) {
                    distinctValues.add(value);
                }
            });
        }

        return distinctValues;
    }

    private static <T> List<String> collectSlugs(
            List<T> items,
            Function<T, String> extractor
    ) {
        List<String> values = new ArrayList<>();
        for (T item : items) {
            if (item != null) {
                values.add(extractor.apply(item));
            }
        }

        return values;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static double orDefault(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    /**
     * 导入过程中的共享状态：用户 id、跳过计数与警告。
     *
     * <p>警告有上限，旧备份里成千上万张失效卡片不能把响应体撑爆。
     */
    private static final class ImportContext {

        private final Long userId;
        private final List<String> warnings = new ArrayList<>();
        private int skipped;

        private ImportContext(Long userId) {
            this.userId = userId;
        }

        private void skip(String message) {
            skipped++;

            if (warnings.size() < MAX_WARNINGS) {
                warnings.add(message);
            } else if (warnings.size() == MAX_WARNINGS) {
                warnings.add("警告过多，其余已省略");
            }
        }
    }
}
