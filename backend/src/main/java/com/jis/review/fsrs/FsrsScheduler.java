package com.jis.review.fsrs;

import java.util.concurrent.ThreadLocalRandom;

import com.jis.review.entity.ReviewState;

/**
 * FSRS-6 记忆模型与调度器。
 *
 * <p>纯函数，不依赖 Spring 也不碰数据库，因此可以独立做单元测试——
 * 算法是这一层唯一容易出错、且出错后很难被用户发现的地方
 * （间隔算错只会表现为「复习节奏怪怪的」，不会报错）。
 *
 * <h2>记忆模型</h2>
 * 三要素：稳定性 S（遗忘到可提取性 0.9 所需天数）、难度 D（1~10）、
 * 可提取性 R（此刻还能回忆起来的概率）。遗忘曲线：
 *
 * <pre>
 *   R(t, S) = (1 + FACTOR · t / S) ^ DECAY
 *   DECAY   = -w20
 *   FACTOR  = 0.9 ^ (1 / DECAY) - 1
 * </pre>
 *
 * 下一次间隔由「希望保留多少可提取性」反解得到，即 {@code desiredRetention}：
 *
 * <pre>
 *   I = S / FACTOR · (desiredRetention ^ (1 / DECAY) - 1)
 * </pre>
 *
 * <h2>与官方实现的差异（有意简化）</h2>
 * <ol>
 *   <li>不实现 Anki 那样的多步学习计划（1 分钟 / 10 分钟两档）。
 *       本实现只保留一个短期步长：评分「不会」或「模糊」时，
 *       卡片在 {@value #SHORT_STEP_MINUTES} 分钟后重现；
 *       评分「会讲」或「轻松」则当天毕业进入长期复习。</li>
 *   <li>不做个性化参数训练（FSRS 的 optimizer 需要用户的历史复习记录
 *       做梯度下降），直接使用官方默认参数。</li>
 * </ol>
 * 除以上两点，稳定性与难度的更新公式、遗忘曲线、间隔反解、
 * 间隔抖动均与官方实现一致。
 */
public class FsrsScheduler {

    /**
     * 短期重现的间隔（分钟）。用于评分「不会」/「模糊」后让卡片在本轮重新出现。
     */
    public static final int SHORT_STEP_MINUTES = 10;

    private static final double STABILITY_MIN = 0.001;
    private static final double MIN_DIFFICULTY = 1.0;
    private static final double MAX_DIFFICULTY = 10.0;

    /**
     * 低于此间隔不做抖动，否则 1~2 天的卡片会抖成 0 或 3，反而失真。
     */
    private static final double MIN_FUZZABLE_INTERVAL = 2.5;

    /**
     * 距上次复习不足一天即视为「同日复习」，走短期稳定性公式。
     * 这与 FSRS 用整数天判断 {@code elapsed_days == 0} 的语义一致。
     */
    private static final double SAME_DAY_THRESHOLD_DAYS = 1.0;

    private final FsrsParameters parameters;
    private final double desiredRetention;
    private final int maximumInterval;
    private final boolean fuzzingEnabled;

    private final double decay;
    private final double factor;

    public FsrsScheduler(
            FsrsParameters parameters,
            double desiredRetention,
            int maximumInterval,
            boolean fuzzingEnabled
    ) {
        if (desiredRetention <= 0 || desiredRetention >= 1) {
            throw new IllegalArgumentException("desiredRetention 必须在 (0, 1) 之间，实际 " + desiredRetention);
        }
        if (maximumInterval < 1) {
            throw new IllegalArgumentException("maximumInterval 必须为正，实际 " + maximumInterval);
        }

        this.parameters = parameters;
        this.desiredRetention = desiredRetention;
        this.maximumInterval = maximumInterval;
        this.fuzzingEnabled = fuzzingEnabled;

        this.decay = -parameters.decay();
        this.factor = Math.pow(0.9, 1 / decay) - 1;
    }

    /**
     * 默认配置：0.9 保留率、最长 10 年、开启抖动。
     */
    public static FsrsScheduler withDefaults() {
        return new FsrsScheduler(FsrsParameters.DEFAULT, 0.9, 36_500, true);
    }

    /**
     * 当前可提取性：距上次复习 {@code elapsedDays} 天后还能回忆起来的概率。
     */
    public double retrievability(double elapsedDays, double stability) {
        if (stability <= 0) {
            return 0;
        }

        return Math.pow(1 + factor * elapsedDays / stability, decay);
    }

    /**
     * 按评分推进记忆状态并算出下次间隔。
     *
     * <p>实现上刻意把两件事分开判断，因为它们依据的条件不同：
     *
     * <ul>
     *   <li><b>稳定性/难度怎么更新</b>：看「距上次复习多久」。不足一天算同日复习，
     *       走短期公式；否则走基于可提取性的长期公式。</li>
     *   <li><b>状态怎么迁移</b>：看评分。这决定了卡片是毕业进入长期复习，
     *       还是很快在本轮重新出现。</li>
     * </ul>
     *
     * <p>为什么同日复习必须单独走短期公式：长期公式的稳定性增幅含
     * {@code (e^((1-R)·w10) - 1)} 项，而同日复习的可提取性 R ≈ 1，
     * 这一项会趋近 0，算出「稳定性原地不动」，等于把记忆模型用在了
     * 它不适用的时间尺度上。短期公式才是为同一轮学习内的重复设计的。
     *
     * <p>需要注意短期公式本身的一个特性：它的增幅系数
     * {@code e^(w17·(rating-3+w18)) · S^-w19} 随稳定性上升而下降，
     * 对已经很牢固的卡片（S 较大）会低于 1 并被夹到下限 1.0，
     * 即**稳定性保持不变但不下降**。这是有意为之——已经记住的内容
     * 同日重复收益本来就很小，夹紧只是保证它不会反过来伤害记忆。
     *
     * @param previous    当前记忆状态；未复习过的卡片传 {@link FsrsCardState#newCard()}
     * @param rating      用户自评
     * @param elapsedDays 距上次复习的天数；首次复习传 0
     */
    public FsrsResult schedule(FsrsCardState previous, FsrsRating rating, double elapsedDays) {
        double stability;
        double difficulty;

        if (previous.isNew()) {
            stability = clampStability(parameters.initialStability(rating));
            difficulty = clampDifficulty(initialDifficultyUnclamped(rating));
        } else {
            difficulty = nextDifficulty(previous.difficulty(), rating);

            boolean sameDayReview = elapsedDays < SAME_DAY_THRESHOLD_DAYS;
            if (previous.isShortTermStage() || sameDayReview) {
                stability = clampStability(shortTermStability(previous.stability(), rating));
            } else {
                double retrievability = retrievability(elapsedDays, previous.stability());
                stability = clampStability(
                        nextStability(
                                previous.difficulty(),
                                previous.stability(),
                                retrievability,
                                rating
                        )
                );
            }
        }

        boolean reintroduceSoon = shouldReintroduceSoon(previous, rating);

        return new FsrsResult(
                nextState(previous, rating),
                stability,
                difficulty,
                reintroduceSoon ? 0 : nextIntervalDays(stability),
                reintroduceSoon
        );
    }

    /**
     * 「不会」必然重现；「模糊」只在新卡或学习阶段重现。
     *
     * <p>已经是长期复习中的卡片评「模糊」，说明用户确实想起来了，
     * 只是吃力而已——把它拉回本轮重现反而会打乱节奏，
     * 所以只压缩间隔（w15 惩罚项）而不重置状态。
     */
    private boolean shouldReintroduceSoon(FsrsCardState previous, FsrsRating rating) {
        if (rating == FsrsRating.AGAIN) {
            return true;
        }

        return rating == FsrsRating.HARD
                && (previous.isNew() || previous.isShortTermStage());
    }

    private int nextState(FsrsCardState previous, FsrsRating rating) {
        if (rating == FsrsRating.AGAIN) {
            boolean wasLongTerm = previous.state() == ReviewState.STATE_REVIEW
                    || previous.state() == ReviewState.STATE_RELEARNING;

            return wasLongTerm ? ReviewState.STATE_RELEARNING : ReviewState.STATE_LEARNING;
        }

        if (shouldReintroduceSoon(previous, rating)) {
            return previous.isNew() ? ReviewState.STATE_LEARNING : previous.state();
        }

        return ReviewState.STATE_REVIEW;
    }

    /**
     * 初始难度 D0 = w4 - e^(w5·(rating-1)) + 1。
     */
    private double initialDifficultyUnclamped(FsrsRating rating) {
        return parameters.initialDifficultyBase()
                - Math.exp(parameters.initialDifficultyDecay() * (rating.value() - 1))
                + 1;
    }

    /**
     * 难度更新：先算线性衰减后的变化量，再向初始难度做均值回归。
     *
     * <p>均值回归这一项（w7）是 FSRS 相对 SM-2 的关键改进之一：
     * 它让难度不会因为连续几次评分而跑偏，长期保持在合理区间。
     */
    private double nextDifficulty(double difficulty, FsrsRating rating) {
        double anchor = initialDifficultyUnclamped(FsrsRating.EASY);
        double delta = -(parameters.difficultyDelta() * (rating.value() - 3));
        double damped = difficulty + (MAX_DIFFICULTY - difficulty) * delta / 9;
        double meanReversion = parameters.difficultyMeanReversion();

        return clampDifficulty(meanReversion * anchor + (1 - meanReversion) * damped);
    }

    private double nextStability(
            double difficulty,
            double stability,
            double retrievability,
            FsrsRating rating
    ) {
        if (rating == FsrsRating.AGAIN) {
            return forgetStability(difficulty, stability, retrievability);
        }

        return recallStability(difficulty, stability, retrievability, rating);
    }

    /**
     * 成功回忆：稳定性只增不减。
     *
     * <pre>
     *   S' = S · (1 + e^w8 · (11 - D) · S^-w9 · (e^((1-R)·w10) - 1) · hardPenalty · easyBonus)
     * </pre>
     *
     * 三项影响可以这样理解：难度越高（D 大）增幅越小；稳定性已经很大时
     * （S 大）增幅被 S^-w9 压住，避免强者恒强导致间隔失控；
     * 回忆越吃力（R 低）增幅越大，因为「艰难回忆成功」本身是强记忆信号。
     */
    private double recallStability(
            double difficulty,
            double stability,
            double retrievability,
            FsrsRating rating
    ) {
        double hardPenalty = rating == FsrsRating.HARD ? parameters.hardPenalty() : 1d;
        double easyBonus = rating == FsrsRating.EASY ? parameters.easyBonus() : 1d;

        return stability * (1
                + Math.exp(parameters.recallStabilityBase())
                * (11 - difficulty)
                * Math.pow(stability, -parameters.recallStabilityDecay())
                * (Math.exp((1 - retrievability) * parameters.recallStabilitySensitivity()) - 1)
                * hardPenalty
                * easyBonus);
    }

    /**
     * 遗忘：稳定性通常大幅下降。
     *
     * <pre>
     *   S' = min( w11 · D^-w12 · ((S+1)^w13 - 1) · e^((1-R)·w14),
     *             S / e^(w17·w18) )
     * </pre>
     *
     * 取两者较小值，是为了给「长期没复习后突然忘了」这种情况兜底，
     * 防止遗忘公式在 S 很大时算出比原来还高的稳定性。
     */
    private double forgetStability(double difficulty, double stability, double retrievability) {
        double longTerm = parameters.forgetStabilityBase()
                * Math.pow(difficulty, -parameters.forgetStabilityDecay())
                * (Math.pow(stability + 1, parameters.forgetStabilityGrowth()) - 1)
                * Math.exp((1 - retrievability) * parameters.forgetStabilitySensitivity());

        double shortTermGuard = stability
                / Math.exp(parameters.shortTermBase() * parameters.shortTermOffset());

        return Math.min(longTerm, shortTermGuard);
    }

    /**
     * 同日内再次复习（学习/重新学习阶段）的稳定性变化：
     *
     * <pre>
     *   S' = S · e^(w17·(rating - 3 + w18)) · S^-w19
     * </pre>
     *
     * 其中「会讲」及以上要求增幅至少为 1，避免同日复习反而把稳定性压低。
     */
    private double shortTermStability(double stability, FsrsRating rating) {
        double increase = Math.exp(
                parameters.shortTermBase() * (rating.value() - 3 + parameters.shortTermOffset())
        ) * Math.pow(stability, -parameters.shortTermDecay());

        if (rating != FsrsRating.AGAIN) {
            increase = Math.max(increase, 1d);
        }

        return stability * increase;
    }

    /**
     * 由稳定性反解间隔天数，并做取整、下限与上限约束、随机抖动。
     */
    public int nextIntervalDays(double stability) {
        double interval = (stability / factor)
                * (Math.pow(desiredRetention, 1 / decay) - 1);

        int rounded = (int) Math.round(interval);
        rounded = Math.max(rounded, 1);
        rounded = Math.min(rounded, maximumInterval);

        return applyFuzzing(rounded);
    }

    /**
     * 给间隔加一点随机抖动。
     *
     * <p>抖动范围随间隔增大而增大（官方给出的三段系数：2.5~7 天 ±15%、
     * 7~20 天 ±10%、20 天以上 ±5%）。目的是把「同一批导入、同一批开始复习」
     * 的卡片到期时间打散，避免某一天复习量突然爆表——这也正是缓存雪崩
     * 在复习计划上的翻版。
     */
    private int applyFuzzing(int intervalDays) {
        if (!fuzzingEnabled || intervalDays < MIN_FUZZABLE_INTERVAL) {
            return intervalDays;
        }

        double delta = 1.0;
        delta += 0.15 * Math.max(Math.min(intervalDays, 7.0) - 2.5, 0.0);
        delta += 0.10 * Math.max(Math.min(intervalDays, 20.0) - 7.0, 0.0);
        delta += 0.05 * Math.max(intervalDays - 20.0, 0.0);

        int minInterval = Math.max(2, (int) Math.round(intervalDays - delta));
        int maxInterval = Math.min(maximumInterval, (int) Math.round(intervalDays + delta));
        minInterval = Math.min(minInterval, maxInterval);

        return ThreadLocalRandom.current()
                .nextInt(minInterval, maxInterval + 1);
    }

    private double clampStability(double stability) {
        return Math.max(stability, STABILITY_MIN);
    }

    private double clampDifficulty(double difficulty) {
        return Math.min(Math.max(difficulty, MIN_DIFFICULTY), MAX_DIFFICULTY);
    }
}
