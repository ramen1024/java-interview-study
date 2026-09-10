package com.jis.review.fsrs;

/**
 * FSRS-6 的 21 个模型参数。
 *
 * <p>字段命名与官方 w<sub>0</sub>~w<sub>20</sub> 一一对应（已在注释中标注），
 * 取值来自官方参考实现的默认参数，可直接用于无需个性化训练的普通用户。
 * 参数含义：
 *
 * <ul>
 *   <li>w0~w3：四档评分各自的初始稳定性（天）</li>
 *   <li>w4、w5：初始难度 D0 的基线与随评分下降的陡峭度</li>
 *   <li>w6：评分带来的难度变化量；w7：难度的均值回归强度</li>
 *   <li>w8~w10：成功回忆时的稳定性增幅（含难度、当前稳定性、可提取性三项影响）</li>
 *   <li>w11~w14：遗忘时的稳定性衰减</li>
 *   <li>w15：Hard 的惩罚系数；w16：Easy 的奖励系数</li>
 *   <li>w17~w19：同日内的短期记忆稳定性变化</li>
 *   <li>w20：遗忘曲线衰减率（decay）</li>
 * </ul>
 */
public record FsrsParameters(

        /* w0 */ double initialStabilityAgain,
        /* w1 */ double initialStabilityHard,
        /* w2 */ double initialStabilityGood,
        /* w3 */ double initialStabilityEasy,
        /* w4 */ double initialDifficultyBase,
        /* w5 */ double initialDifficultyDecay,
        /* w6 */ double difficultyDelta,
        /* w7 */ double difficultyMeanReversion,
        /* w8 */ double recallStabilityBase,
        /* w9 */ double recallStabilityDecay,
        /* w10 */ double recallStabilitySensitivity,
        /* w11 */ double forgetStabilityBase,
        /* w12 */ double forgetStabilityDecay,
        /* w13 */ double forgetStabilityGrowth,
        /* w14 */ double forgetStabilitySensitivity,
        /* w15 */ double hardPenalty,
        /* w16 */ double easyBonus,
        /* w17 */ double shortTermBase,
        /* w18 */ double shortTermOffset,
        /* w19 */ double shortTermDecay,
        /* w20 */ double decay
) {

    /**
     * 官方默认参数（FSRS-6）。
     */
    public static final FsrsParameters DEFAULT = new FsrsParameters(
            0.212,
            1.2931,
            2.3065,
            8.2956,
            6.4133,
            0.8334,
            3.0194,
            0.001,
            1.8722,
            0.1666,
            0.796,
            1.4835,
            0.0614,
            0.2629,
            1.6483,
            0.6014,
            1.8729,
            0.5425,
            0.0912,
            0.0658,
            0.1542
    );

    public double initialStability(FsrsRating rating) {
        return switch (rating) {
            case AGAIN -> initialStabilityAgain;
            case HARD -> initialStabilityHard;
            case GOOD -> initialStabilityGood;
            case EASY -> initialStabilityEasy;
        };
    }
}
