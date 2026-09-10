package com.jis.content.dto;

/**
 * 关联知识点（双链的出口）。
 *
 * <p>指向尚未编写知识点的关联在导入时就已被跳过（记录 WARN），
 * 所以这里出现的每一条都保证可以点进去。
 *
 * @param relationType PREREQUISITE 前置 / DEEPEN 深入 / CONTRAST 对比 / RELATED 相关
 */
public record RelatedKpVO(

        String slug,

        String title,

        String moduleName,

        String relationType,

        int difficulty,

        int frequency
) {
}
