package com.jis.review.fsrs;

import com.jis.review.entity.ReviewState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FSRS 调度器的单元测试。
 *
 * <p>算法出错不会抛异常，只会表现为「复习节奏怪怪的」这类难察觉的问题，
 * 所以这里测的是**不变量**而不是复刻公式：间隔随稳定性单调、
 * 四档评分顺序正确、稳定性在成功回忆后只增不减、遗忘后下降、
 * 以及「按算出的间隔去复习时，可提取性正好等于目标保留率」。
 */
class FsrsSchedulerTest {

    /**
     * 关闭抖动，让结果可断言。
     */
    private final FsrsScheduler scheduler = new FsrsScheduler(
            FsrsParameters.DEFAULT,
            0.9,
            36_500,
            false
    );

    @Test
    @DisplayName("新卡片首次评分用初始参数，不套用更新公式")
    void firstReviewUsesInitialParameters() {
        FsrsResult result = scheduler.schedule(
                FsrsCardState.newCard(),
                FsrsRating.GOOD,
                0
        );

        assertEquals(ReviewState.STATE_REVIEW, result.state());
        assertEquals(2.3065, result.stability(), 1e-9, "GOOD 的初始稳定性应为 w2");
        // D0 = w4 - e^(w5·(rating-1)) + 1，GOOD 的 rating 是 3，故指数为 2
        assertEquals(
                6.4133 - Math.exp(0.8334 * 2) + 1,
                result.difficulty(),
                1e-3
        );
        assertTrue(result.intervalDays() >= 1, "间隔至少 1 天");
    }

    @Test
    @DisplayName("目标保留率为 0.9 时，算出的间隔在数值上等于稳定性")
    void intervalEqualsStabilityAtNinetyPercentRetention() {
        // I = S / FACTOR · (0.9^(1/DECAY) - 1)，而 FACTOR 本身就是 0.9^(1/DECAY) - 1，
        // 所以保留率取 0.9 时间隔应恰好等于稳定性
        assertEquals(100, scheduler.nextIntervalDays(100d));
        assertEquals(1, scheduler.nextIntervalDays(0.4d), "不足 1 天向上取到 1 天");
    }

    @Test
    @DisplayName("按算出的间隔复习时，可提取性回到目标保留率")
    void retrievabilityMatchesDesiredRetentionAtScheduledInterval() {
        double stability = 100d;
        int interval = scheduler.nextIntervalDays(stability);

        double retrievability = scheduler.retrievability(interval, stability);

        assertEquals(0.9, retrievability, 1e-9, "这正是间隔反解公式的定义");
    }

    @Test
    @DisplayName("可提取性随间隔推移单调下降")
    void retrievabilityDecreasesOverTime() {
        double stability = 10d;

        double day0 = scheduler.retrievability(0, stability);
        double day5 = scheduler.retrievability(5, stability);
        double day30 = scheduler.retrievability(30, stability);

        assertEquals(1.0, day0, 1e-9);
        assertTrue(day5 < day0 && day30 < day5, "应为单调下降");
    }

    @Test
    @DisplayName("四档评分严格区分：Easy 间隔 > Good > Hard，Again 立即重现")
    void ratingsAreOrderedByAggressiveness() {
        int easy = scheduler.schedule(FsrsCardState.newCard(), FsrsRating.EASY, 0).intervalDays();
        int good = scheduler.schedule(FsrsCardState.newCard(), FsrsRating.GOOD, 0).intervalDays();
        int hard = scheduler.schedule(FsrsCardState.newCard(), FsrsRating.HARD, 0).intervalDays();

        assertTrue(hard <= good, "Hard 的间隔不应超过 Good，实际 " + hard + " vs " + good);
        assertTrue(good < easy, "Good 的间隔应小于 Easy，实际 " + good + " vs " + easy);

        FsrsResult again = scheduler.schedule(FsrsCardState.newCard(), FsrsRating.AGAIN, 0);
        assertEquals(ReviewState.STATE_LEARNING, again.state(), "「不会」应进入学习阶段");
        assertTrue(again.reintroduceSoon(), "「不会」应安排本轮内重现");
        assertEquals(0, again.intervalDays(), "本轮重现不走天级间隔");
    }

    @Test
    @DisplayName("Easy 把新卡难度压到下限 1.0，Again 抬到上限附近")
    void difficultyIsClampedForExtremeRatings() {
        double easyDifficulty = scheduler.schedule(FsrsCardState.newCard(), FsrsRating.EASY, 0)
                .difficulty();
        double againDifficulty = scheduler.schedule(FsrsCardState.newCard(), FsrsRating.AGAIN, 0)
                .difficulty();

        // D0(EASY) = w4 - e^(3·w5) + 1 ≈ -4.77，应被夹到下限 1.0
        assertEquals(1.0, easyDifficulty, 1e-9);
        assertEquals(6.4133, againDifficulty, 1e-3);
    }

    @Test
    @DisplayName("持续评「会讲」时稳定性单调递增，间隔随之拉长")
    void stabilityGrowsOnRepeatedSuccessfulReviews() {
        FsrsCardState state = FsrsCardState.newCard();
        FsrsResult result = scheduler.schedule(state, FsrsRating.GOOD, 0);

        double previousStability = result.stability();
        int previousInterval = result.intervalDays();

        for (int round = 0; round < 5; round++) {
            state = new FsrsCardState(result.state(), result.stability(), result.difficulty());
            result = scheduler.schedule(state, FsrsRating.GOOD, result.intervalDays());

            assertTrue(
                    result.stability() > previousStability,
                    "第 " + (round + 2) + " 次复习的稳定性应高于上次："
                            + result.stability() + " vs " + previousStability
            );
            assertTrue(
                    result.intervalDays() >= previousInterval,
                    "间隔不应缩短：" + result.intervalDays() + " vs " + previousInterval
            );

            previousStability = result.stability();
            previousInterval = result.intervalDays();
        }

        assertTrue(previousInterval > 30, "5 轮通过后间隔应显著拉长，实际 " + previousInterval + " 天");
    }

    @Test
    @DisplayName("遗忘时稳定性下降并退回重新学习阶段")
    void forgettingDropsStabilityAndEntersRelearning() {
        FsrsCardState state = FsrsCardState.newCard();
        FsrsResult result = scheduler.schedule(state, FsrsRating.GOOD, 0);

        for (int round = 0; round < 4; round++) {
            state = new FsrsCardState(result.state(), result.stability(), result.difficulty());
            result = scheduler.schedule(state, FsrsRating.GOOD, result.intervalDays());
        }

        double stabilityBeforeForgetting = result.stability();
        state = new FsrsCardState(result.state(), result.stability(), result.difficulty());

        FsrsResult forgotten = scheduler.schedule(state, FsrsRating.AGAIN, result.intervalDays());

        assertEquals(ReviewState.STATE_RELEARNING, forgotten.state());
        assertTrue(forgotten.reintroduceSoon());
        assertTrue(
                forgotten.stability() < stabilityBeforeForgetting,
                "遗忘后稳定性应下降：" + forgotten.stability() + " vs " + stabilityBeforeForgetting
        );
    }

    @Test
    @DisplayName("难度越高，同样的成功回忆带来的稳定性增幅越小")
    void higherDifficultyYieldsSmallerStabilityGain() {
        double elapsedDays = 5d;
        double stability = 10d;

        double easyGain = scheduler.schedule(
                new FsrsCardState(ReviewState.STATE_REVIEW, stability, 2d),
                FsrsRating.GOOD,
                elapsedDays
        ).stability();

        double hardGain = scheduler.schedule(
                new FsrsCardState(ReviewState.STATE_REVIEW, stability, 9d),
                FsrsRating.GOOD,
                elapsedDays
        ).stability();

        assertTrue(
                easyGain > hardGain,
                "低难度的稳定性增幅应更大：" + easyGain + " vs " + hardGain
        );
    }

    @Test
    @DisplayName("间隔不超过上限")
    void intervalIsCappedAtMaximum() {
        FsrsScheduler capped = new FsrsScheduler(FsrsParameters.DEFAULT, 0.9, 365, false);

        assertEquals(365, capped.nextIntervalDays(100_000d));
    }

    @Test
    @DisplayName("学习阶段的卡片评「会讲」后毕业进入长期复习")
    void learningCardGraduatesAfterGoodRating() {
        FsrsCardState learning = new FsrsCardState(ReviewState.STATE_LEARNING, 0.212, 6.4133);

        FsrsResult graduated = scheduler.schedule(learning, FsrsRating.GOOD, 0);
        assertEquals(ReviewState.STATE_REVIEW, graduated.state());
        assertTrue(graduated.intervalDays() >= 1);

        FsrsResult stillLearning = scheduler.schedule(learning, FsrsRating.HARD, 0);
        assertEquals(ReviewState.STATE_LEARNING, stillLearning.state());
        assertTrue(stillLearning.reintroduceSoon(), "「模糊」也应在本轮重现");
    }

    @Test
    @DisplayName("同日重复复习走短期公式：低稳定性仍有增长，高稳定性保持不变")
    void sameDayReviewUsesShortTermFormula() {
        double twoHours = 2d / 24d;

        // 已经很牢固的卡片（S=10）：短期增幅系数 S^-w19 使其低于 1，
        // 会被夹到下限 1.0，于是稳定性恰好不变——而不是下降
        FsrsResult wellLearned = scheduler.schedule(
                new FsrsCardState(ReviewState.STATE_REVIEW, 10d, 5d),
                FsrsRating.GOOD,
                twoHours
        );
        assertEquals(10d, wellLearned.stability(), 1e-9, "不得因为同日重复复习而下降");
        assertEquals(ReviewState.STATE_REVIEW, wellLearned.state());

        // 还不牢固的卡片（S=0.5）：同一公式给出大于 1 的增幅，稳定性确实提升
        FsrsResult shaky = scheduler.schedule(
                new FsrsCardState(ReviewState.STATE_REVIEW, 0.5d, 7d),
                FsrsRating.GOOD,
                twoHours
        );
        assertTrue(
                shaky.stability() > 0.5d,
                "稳定性低时同日复习应仍有增长，实际 " + shaky.stability()
        );
    }

    @Test
    @DisplayName("长期复习中的卡片评「模糊」只压缩间隔，不退回本轮重现")
    void hardOnReviewCardCompressesIntervalWithoutResetting() {
        FsrsCardState reviewing = new FsrsCardState(ReviewState.STATE_REVIEW, 10d, 5d);

        FsrsResult hard = scheduler.schedule(reviewing, FsrsRating.HARD, 10d);
        FsrsResult good = scheduler.schedule(reviewing, FsrsRating.GOOD, 10d);

        assertEquals(ReviewState.STATE_REVIEW, hard.state());
        assertTrue(!hard.reintroduceSoon(), "已毕业的卡片评「模糊」不应回到本轮队列");
        assertTrue(
                hard.stability() < good.stability(),
                "Hard 的惩罚项应让稳定性增幅小于 Good：" + hard.stability() + " vs " + good.stability()
        );
    }

    @Test
    @DisplayName("开启抖动后间隔落在合理范围内且被打散")
    void fuzzingKeepsIntervalInReasonableRange() {
        FsrsScheduler fuzzed = new FsrsScheduler(FsrsParameters.DEFAULT, 0.9, 36_500, true);

        boolean sawDifferentValue = false;
        for (int attempt = 0; attempt < 200; attempt++) {
            int jittered = fuzzed.nextIntervalDays(50d);

            assertTrue(jittered >= 40 && jittered <= 60, "抖动幅度不应过大，实际 " + jittered);
            if (jittered != 50) {
                sawDifferentValue = true;
            }
        }

        assertTrue(sawDifferentValue, "抖动应产生与基准值不同的结果");
    }

    @Test
    @DisplayName("短间隔不做抖动，避免 1~2 天的卡片被抖成 0 天")
    void shortIntervalsAreNotFuzzed() {
        FsrsScheduler fuzzed = new FsrsScheduler(FsrsParameters.DEFAULT, 0.9, 36_500, true);

        assertEquals(1, fuzzed.nextIntervalDays(0.9d));
        assertEquals(2, fuzzed.nextIntervalDays(2.0d));
    }
}
