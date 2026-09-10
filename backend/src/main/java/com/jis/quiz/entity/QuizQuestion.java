package com.jis.quiz.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("quiz_question")
public class QuizQuestion {

    /**
     * 题型取值。CLOZE 用 blanksJson 判分，不用 answer。
     */
    public static final String TYPE_CHOICE = "CHOICE";
    public static final String TYPE_MULTI = "MULTI";
    public static final String TYPE_JUDGE = "JUDGE";
    public static final String TYPE_CLOZE = "CLOZE";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kpId;

    /**
     * 全局稳定键 {@code <卡片slug>-<序号>}，导入时以它 upsert。
     */
    private String qKey;

    private String type;

    private String stemMd;

    /**
     * JSON 数组 {@code [{"key":"A","text":"..."}]}，仅选择题有值。
     */
    private String optionsJson;

    /**
     * CHOICE: A；MULTI: ABC；JUDGE: T/F；CLOZE 为空。
     */
    private String answer;

    /**
     * JSON：每空可接受答案的数组，如 {@code [["尾插","尾部插入"]]}。
     */
    private String blanksJson;

    private String analysisMd;

    private Integer difficulty;

    private String sourcePath;

    private Integer sort;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
