package com.jis.backup.dto;

import java.util.List;

/**
 * 一次导入的结果。
 *
 * <p>各 {@code ...Count} 是实际写入（新增或按业务键覆盖）的条数；
 * {@code skippedCount} 是目标卡片/题目已不存在、文件缺字段或答题记录重复
 * 等原因未写入的条数，具体原因在 {@code warnings} 里。
 */
public record BackupSummaryVO(

        int reviewStateCount,

        int noteCount,

        int favoriteCount,

        int quizRecordCount,

        int studyDailyCount,

        int skippedCount,

        List<String> warnings
) {

    /**
     * 用户可感知的「恢复条数」。
     */
    public int restoredTotal() {
        return reviewStateCount
                + noteCount
                + favoriteCount
                + quizRecordCount
                + studyDailyCount;
    }
}
