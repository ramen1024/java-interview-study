package com.jis.stats.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 每日学习统计。热力图与连续打卡都读这张表。
 *
 * <p>刻意做成累加表而不是每次实时聚合 {@code review_log}：
 * 热力图要一次查一年，聚合 365 天的明细在数据量上来后会明显变慢，
 * 而这里每天只有一行。
 */
@Data
@TableName("study_daily")
public class StudyDaily {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private LocalDate statDate;

    private Integer reviewCount;

    private Integer newCount;

    private Integer quizCount;

    private Integer correctCount;

    private Integer durationSec;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
