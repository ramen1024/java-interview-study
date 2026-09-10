package com.jis.review.fsrs;

import com.jis.common.BizException;
import com.jis.common.ResultCode;

/**
 * FSRS 的四档评分。
 *
 * <p>取值与 {@code review_state.rating} 列一致，也对应 FSRS 官方参数中
 * 第 0~3 号权重（初始稳定性）的下标顺序。
 */
public enum FsrsRating {

    /**
     * 完全没想起来。会重置记忆状态，卡片很快重新出现。
     */
    AGAIN(1, "不会"),

    /**
     * 想起来了但很吃力。
     */
    HARD(2, "模糊"),

    /**
     * 正常想起来了，标准档。
     */
    GOOD(3, "会讲"),

    /**
     * 脱口而出，稳定性会获得额外加成。
     */
    EASY(4, "轻松");

    private final int value;
    private final String label;

    FsrsRating(int value, String label) {
        this.value = value;
        this.label = label;
    }

    public int value() {
        return value;
    }

    public String label() {
        return label;
    }

    public static FsrsRating of(int value) {
        for (FsrsRating rating : values()) {
            if (rating.value == value) {
                return rating;
            }
        }

        throw new BizException(
                ResultCode.RATING_INVALID,
                "评分取值只能是 1~4，实际 " + value
        );
    }
}
