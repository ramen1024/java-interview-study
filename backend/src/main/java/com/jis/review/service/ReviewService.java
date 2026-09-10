package com.jis.review.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jis.common.BizException;
import com.jis.common.ResultCode;
import com.jis.content.dto.KnowledgePointListItemVO;
import com.jis.content.mapper.KnowledgePointMapper;
import com.jis.content.service.ContentQueryService;
import com.jis.review.ReviewProperties;
import com.jis.review.dto.RateRequest;
import com.jis.review.dto.RateResultVO;
import com.jis.review.dto.ReviewQueueVO;
import com.jis.review.entity.ReviewLog;
import com.jis.review.entity.ReviewState;
import com.jis.review.fsrs.FsrsCardState;
import com.jis.review.fsrs.FsrsRating;
import com.jis.review.fsrs.FsrsResult;
import com.jis.review.fsrs.FsrsScheduler;
import com.jis.review.mapper.ReviewLogMapper;
import com.jis.review.mapper.ReviewStateMapper;
import com.jis.stats.mapper.StudyDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 间隔重复复习。
 *
 * <p>队列由「到期卡 + 每日配额内的新卡」拼成，评分后交给
 * {@link FsrsScheduler} 推进记忆状态并算出下次到期时间。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private static final int MAX_QUEUE_LIMIT = 200;

    private final ReviewProperties properties;
    private final FsrsScheduler fsrsScheduler;
    private final ContentQueryService contentQueryService;
    private final KnowledgePointMapper knowledgePointMapper;
    private final ReviewStateMapper reviewStateMapper;
    private final ReviewLogMapper reviewLogMapper;
    private final StudyDailyMapper studyDailyMapper;

    /**
     * 构建今日复习队列。
     *
     * @param moduleSlug 只复习某个模块（含其子模块）；为 null 表示全部
     * @param limit      单次最多返回多少张；为 null 用配置默认值
     */
    public ReviewQueueVO buildQueue(Long userId, String moduleSlug, Integer limit) {
        LocalDateTime now = LocalDateTime.now();
        Set<Long> moduleIds = resolveModuleIds(moduleSlug);

        int wanted = limit == null
                ? properties.dueCardsPerRequest()
                : Math.min(Math.max(limit, 1), MAX_QUEUE_LIMIT);

        long dueCount = reviewStateMapper.countDue(userId, now, moduleIds);

        List<Long> dueKpIds = reviewStateMapper.selectDueKpIds(userId, now, moduleIds, wanted);
        int remainingSlots = wanted - dueKpIds.size();

        long introducedToday = reviewStateMapper.countIntroducedSince(
                userId,
                LocalDate.now()
                        .atStartOfDay()
        );
        long remainingQuota = Math.max(0, properties.newCardsPerDay() - introducedToday);

        List<Long> newKpIds = List.of();
        if (remainingSlots > 0 && remainingQuota > 0) {
            int newTake = (int) Math.min(remainingSlots, remainingQuota);
            newKpIds = knowledgePointMapper.selectNewKpIds(userId, moduleIds, newTake);
        }

        // 到期卡在前（最逾期的优先），新卡补在后面
        List<Long> orderedIds = new ArrayList<>(dueKpIds);
        orderedIds.addAll(newKpIds);

        List<KnowledgePointListItemVO> items = contentQueryService.describeCards(orderedIds, userId);

        return new ReviewQueueVO(
                items,
                dueCount,
                newKpIds.size(),
                remainingQuota - newKpIds.size()
        );
    }

    /**
     * 提交一次自评，推进该卡片的记忆状态。
     *
     * <p>提前复习是允许的（用户可能想反复看某个知识点），
     * 此时可提取性按实际经过了多久计算，间隔会相应缩短——
     * 这比拒绝请求或悄悄忽略更符合直觉。
     */
    @Transactional
    public RateResultVO rate(Long userId, String slug, RateRequest request) {
        FsrsRating rating = FsrsRating.of(request.rating());
        Long kpId = contentQueryService.requireKpId(slug);

        ReviewState state = reviewStateMapper.selectOne(
                Wrappers.<ReviewState>lambdaQuery()
                        .eq(ReviewState::getUserId, userId)
                        .eq(ReviewState::getKpId, kpId)
        );

        FsrsCardState previous = FsrsCardState.from(state);
        double elapsedDays = elapsedDays(state);
        Double stabilityBefore = state == null ? null : state.getStability();

        FsrsResult result = fsrsScheduler.schedule(previous, rating, elapsedDays);

        // 截到秒：due_at 列本身没有小数秒，不截断会让响应里的时间
        // 与实际入库的值不一致（前端显示 20:42:02.926 而库里是 20:42:02）
        LocalDateTime now = LocalDateTime.now()
                .withNano(0);
        LocalDateTime dueAt = result.reintroduceSoon()
                ? now.plusMinutes(FsrsScheduler.SHORT_STEP_MINUTES)
                : LocalDate.now()
                        .plusDays(result.intervalDays())
                        .atStartOfDay();

        boolean firstReview = state == null;
        if (firstReview) {
            state = new ReviewState();
            state.setUserId(userId);
            state.setKpId(kpId);
            state.setReps(0);
            state.setLapses(0);
        }

        state.setState(result.state());
        state.setStability(result.stability());
        state.setDifficulty(result.difficulty());
        state.setReps(state.getReps() + 1);
        if (rating == FsrsRating.AGAIN) {
            state.setLapses(state.getLapses() + 1);
        }
        state.setDueAt(dueAt);
        state.setLastReviewAt(now);

        if (firstReview) {
            reviewStateMapper.insert(state);
        } else {
            reviewStateMapper.updateById(state);
        }

        writeReviewLog(
                userId,
                kpId,
                rating,
                request.durationMsOrDefault(),
                result,
                elapsedDays,
                now
        );

        studyDailyMapper.accumulateReview(
                userId,
                LocalDate.now(),
                1,
                firstReview ? 1 : 0,
                request.durationMsOrDefault() / 1000
        );

        return new RateResultVO(
                rating.value(),
                rating.label(),
                result.state(),
                ReviewState.labelOf(result.state()),
                stabilityBefore,
                result.stability(),
                result.difficulty(),
                result.intervalDays(),
                result.reintroduceSoon(),
                dueAt.toString()
        );
    }

    /**
     * 重置某张卡的复习进度，让它回到「新卡」状态。
     *
     * <p>刻意**不删除** {@code review_log}：日志是只追加的学习历史，
     * 用于统计和后续给 FSRS 调参，重置进度不应该抹掉「我曾经学过」这个事实。
     */
    @Transactional
    public void reset(Long userId, String slug) {
        Long kpId = contentQueryService.requireKpId(slug);

        reviewStateMapper.delete(
                Wrappers.<ReviewState>lambdaQuery()
                        .eq(ReviewState::getUserId, userId)
                        .eq(ReviewState::getKpId, kpId)
        );

        log.info("已重置复习进度 userId={} slug={}", userId, slug);
    }

    private void writeReviewLog(
            Long userId,
            Long kpId,
            FsrsRating rating,
            int durationMs,
            FsrsResult result,
            double elapsedDays,
            LocalDateTime reviewedAt
    ) {
        ReviewLog logEntry = new ReviewLog();
        logEntry.setUserId(userId);
        logEntry.setKpId(kpId);
        logEntry.setRating(rating.value());
        logEntry.setDurationMs(durationMs);
        logEntry.setStabilityAfter(result.stability());
        logEntry.setDifficultyAfter(result.difficulty());
        logEntry.setIntervalDays(
                result.reintroduceSoon() ? 0d : (double) result.intervalDays()
        );
        logEntry.setReviewedAt(reviewedAt);

        reviewLogMapper.insert(logEntry);

        if (log.isDebugEnabled()) {
            log.debug(
                    "复习评分 kpId={} rating={} 距上次 {} 天 → 稳定性 {} 天，间隔 {} 天",
                    kpId,
                    rating.label(),
                    Math.round(elapsedDays * 10) / 10d,
                    Math.round(result.stability() * 100) / 100d,
                    result.intervalDays()
            );
        }
    }

    /**
     * 距上次复习过了多少天。
     *
     * <p>按分钟换算而不是按日期差：日内复习（「不会」后 10 分钟重现）
     * 必须能算出小于 1 的小数，否则会被当成「已经过了一整天」而低估记忆强度。
     */
    private double elapsedDays(ReviewState state) {
        if (state == null || state.getLastReviewAt() == null) {
            return 0d;
        }

        long minutes = Duration.between(state.getLastReviewAt(), LocalDateTime.now())
                .toMinutes();

        return Math.max(minutes / (60d * 24d), 0d);
    }

    private Set<Long> resolveModuleIds(String moduleSlug) {
        Set<Long> moduleIds = contentQueryService.resolveModuleIds(moduleSlug);

        if (moduleIds != null && moduleIds.isEmpty()) {
            throw new BizException(ResultCode.MODULE_NOT_FOUND);
        }

        return moduleIds;
    }
}
