package com.jis.stats.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jis.content.dto.ModuleAggregate;
import com.jis.content.entity.ContentModule;
import com.jis.content.mapper.ContentModuleMapper;
import com.jis.content.mapper.KnowledgePointMapper;
import com.jis.review.ReviewProperties;
import com.jis.review.entity.ReviewLog;
import com.jis.review.entity.ReviewState;
import com.jis.review.mapper.ReviewLogMapper;
import com.jis.review.mapper.ReviewStateMapper;
import com.jis.stats.dto.HeatmapDayVO;
import com.jis.stats.dto.HeatmapVO;
import com.jis.stats.dto.ModuleMasteryVO;
import com.jis.stats.dto.OverviewVO;
import com.jis.stats.entity.StudyDaily;
import com.jis.stats.mapper.StudyDailyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 学习统计。只读聚合，全部走数据库的 GROUP BY，不在应用层做全量遍历。
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    /**
     * 连续打卡最多回溯多少天。超过这个长度的连续记录会被截断，
     * 因为查询窗口本身有上限——这是有意的取舍，不为了一个纪念性数字
     * 去扫全部历史。
     */
    private static final int MAX_STREAK_LOOKBACK = 400;

    /**
     * 热力图强度分级阈值。用固定阈值而不是动态分位数，
     * 是为了让颜色含义在不同用户、不同时间之间保持可比。
     */
    private static final int[] HEATMAP_THRESHOLDS = {1, 5, 15, 30};

    private final ReviewProperties reviewProperties;
    private final KnowledgePointMapper knowledgePointMapper;
    private final ContentModuleMapper contentModuleMapper;
    private final ReviewStateMapper reviewStateMapper;
    private final ReviewLogMapper reviewLogMapper;
    private final StudyDailyMapper studyDailyMapper;

    public OverviewVO overview(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();

        long totalCards = knowledgePointMapper.selectCount(null);
        long trackedCards = reviewStateMapper.selectCount(
                Wrappers.<ReviewState>lambdaQuery()
                        .eq(ReviewState::getUserId, userId)
        );
        long masteredCards = reviewStateMapper.selectCount(
                Wrappers.<ReviewState>lambdaQuery()
                        .eq(ReviewState::getUserId, userId)
                        .ge(
                                ReviewState::getStability,
                                reviewProperties.masteredStabilityDays()
                                        .doubleValue()
                        )
        );

        long introducedToday = reviewStateMapper.countIntroducedSince(userId, todayStart);
        long remainingQuota = Math.max(0, reviewProperties.newCardsPerDay() - introducedToday);

        long reviewedToday = reviewLogMapper.selectCount(
                Wrappers.<ReviewLog>lambdaQuery()
                        .eq(ReviewLog::getUserId, userId)
                        .ge(ReviewLog::getReviewedAt, todayStart)
        );

        StudyDaily todayStat = studyDailyMapper.selectOne(
                Wrappers.<StudyDaily>lambdaQuery()
                        .eq(StudyDaily::getUserId, userId)
                        .eq(StudyDaily::getStatDate, today)
        );

        long quizToday = todayStat == null ? 0 : todayStat.getQuizCount();
        long correctToday = todayStat == null ? 0 : todayStat.getCorrectCount();

        return new OverviewVO(
                reviewStateMapper.countDue(userId, now, null),
                knowledgePointMapper.countNew(userId, null),
                remainingQuota,
                reviewedToday,
                quizToday,
                quizToday == 0 ? null : (int) Math.round(correctToday * 100d / quizToday),
                computeStreak(userId),
                totalCards,
                trackedCards,
                masteredCards
        );
    }

    public HeatmapVO heatmap(Long userId, Integer year) {
        int resolvedYear = year == null ? LocalDate.now()
                .getYear() : year;
        LocalDate start = LocalDate.of(resolvedYear, 1, 1);
        LocalDate end = LocalDate.of(resolvedYear, 12, 31);

        List<StudyDaily> rows = studyDailyMapper.selectBetween(userId, start, end);

        List<HeatmapDayVO> days = new ArrayList<>();
        int totalCount = 0;

        for (StudyDaily row : rows) {
            int reviewCount = row.getReviewCount() == null ? 0 : row.getReviewCount();
            int quizCount = row.getQuizCount() == null ? 0 : row.getQuizCount();
            int count = reviewCount + quizCount;
            if (count == 0) {
                continue;
            }

            totalCount += count;
            days.add(new HeatmapDayVO(
                    row.getStatDate()
                            .toString(),
                    count,
                    reviewCount,
                    quizCount,
                    levelOf(count)
            ));
        }

        return new HeatmapVO(resolvedYear, totalCount, days.size(), days);
    }

    /**
     * 各模块的掌握度。父模块包含子模块，供雷达图与明细列表共用。
     */
    public List<ModuleMasteryVO> moduleMastery(Long userId) {
        Map<Long, ModuleAggregate> aggregateByModuleId = new LinkedHashMap<>();
        for (ModuleAggregate aggregate : knowledgePointMapper.aggregateByModule(
                userId,
                reviewProperties.masteredStabilityDays()
                        .doubleValue()
        )) {
            aggregateByModuleId.put(aggregate.getModuleId(), aggregate);
        }

        List<ContentModule> modules = contentModuleMapper.selectList(
                Wrappers.<ContentModule>lambdaQuery()
                        .orderByAsc(ContentModule::getSort)
                        .orderByAsc(ContentModule::getId)
        );

        Map<Long, List<ContentModule>> childrenByParent = new LinkedHashMap<>();
        List<ContentModule> roots = new ArrayList<>();
        for (ContentModule module : modules) {
            if (module.getParentId() == null || module.getParentId() == 0L) {
                roots.add(module);
                continue;
            }
            childrenByParent.computeIfAbsent(module.getParentId(), key -> new ArrayList<>())
                    .add(module);
        }

        return roots.stream()
                .map(root -> buildMasteryNode(root, childrenByParent, aggregateByModuleId))
                .sorted(Comparator.comparing(ModuleMasteryVO::name))
                .toList();
    }

    private ModuleMasteryVO buildMasteryNode(
            ContentModule module,
            Map<Long, List<ContentModule>> childrenByParent,
            Map<Long, ModuleAggregate> aggregateByModuleId
    ) {
        List<ModuleMasteryVO> children = childrenByParent.getOrDefault(module.getId(), List.of())
                .stream()
                .map(child -> buildMasteryNode(child, childrenByParent, aggregateByModuleId))
                .toList();

        ModuleAggregate own = aggregateByModuleId.get(module.getId());

        int total = (own == null || own.getTotal() == null ? 0 : own.getTotal().intValue())
                + children.stream()
                .mapToInt(ModuleMasteryVO::total)
                .sum();
        int mastered = (own == null || own.getMastered() == null ? 0 : own.getMastered().intValue())
                + children.stream()
                .mapToInt(ModuleMasteryVO::mastered)
                .sum();
        int started = (own == null || own.getStarted() == null ? 0 : own.getStarted().intValue())
                + children.stream()
                .mapToInt(ModuleMasteryVO::started)
                .sum();

        return new ModuleMasteryVO(
                module.getSlug(),
                module.getName(),
                total,
                started,
                mastered,
                total == 0 ? 0 : (int) Math.round(mastered * 100d / total),
                children
        );
    }

    /**
     * 连续学习天数。
     *
     * <p>今天还没学习时从昨天开始数：否则每天凌晨打开应用都会看到
     * 连续天数归零，体验上像是「被罚了一次」。
     */
    private int computeStreak(Long userId) {
        LocalDate today = LocalDate.now();

        Set<LocalDate> activeDays = studyDailyMapper.selectActiveSince(
                        userId,
                        today.minusDays(MAX_STREAK_LOOKBACK)
                )
                .stream()
                .map(StudyDaily::getStatDate)
                .collect(Collectors.toSet());

        LocalDate cursor = today;
        if (!activeDays.contains(cursor)) {
            cursor = cursor.minusDays(1);
            if (!activeDays.contains(cursor)) {
                return 0;
            }
        }

        int streak = 0;
        while (activeDays.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }

        return streak;
    }

    private int levelOf(int count) {
        for (int level = HEATMAP_THRESHOLDS.length; level >= 1; level--) {
            if (count >= HEATMAP_THRESHOLDS[level - 1]) {
                return level;
            }
        }

        return 0;
    }
}
