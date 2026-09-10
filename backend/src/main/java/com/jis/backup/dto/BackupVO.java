package com.jis.backup.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 学习数据备份包，导出的 JSON 根对象。
 *
 * <p>只承载「用户行为」数据，不含内容本身——卡片与题目仍然以
 * {@code content/} 下的 Markdown 为唯一事实源。因此备份里的关联一律用业务键
 * （卡片 {@code slug}、题目 {@code qKey}），绝不出现自增 id：
 * 既让文件可读，也避开 BIGINT 超出 JS 安全整数范围的精度问题。
 *
 * <p>{@code version} 用于拒绝误导入其他格式的文件，当前固定为 {@link #CURRENT_VERSION}。
 */
public record BackupVO(

        int version,

        LocalDateTime exportedAt,

        List<ReviewStateItem> reviewStates,

        List<NoteItem> notes,

        List<FavoriteItem> favorites,

        List<QuizRecordItem> quizRecords,

        List<StudyDailyItem> studyDaily
) {

    public static final int CURRENT_VERSION = 1;
}
