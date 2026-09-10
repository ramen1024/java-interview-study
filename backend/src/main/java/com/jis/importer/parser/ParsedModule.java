package com.jis.importer.parser;

import java.util.List;

/**
 * 一个 {@code _module.yml} 的解析结果。
 *
 * @param directoryPath 所属目录的绝对路径，用于推断父子关系与生成稳定排序
 * @param parentSlug    最近的祖先模块 slug；一级模块为 null
 * @param level         1 一级 / 2 二级
 */
public record ParsedModule(

        String slug,

        String name,

        String description,

        String icon,

        int sort,

        String parentSlug,

        int level,

        String directoryPath
) {
}
