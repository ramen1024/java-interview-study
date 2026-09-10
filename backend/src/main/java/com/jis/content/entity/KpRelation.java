package com.jis.content.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 知识点之间的双链。方向有意义：推荐"下一步看什么"用 from → to。
 */
@Data
@TableName("kp_relation")
public class KpRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long fromKpId;

    private Long toKpId;

    /**
     * RELATED / PREREQUISITE / CONTRAST / DEEPEN。
     */
    private String relationType;

    private Integer sort;

    private LocalDateTime createTime;
}
