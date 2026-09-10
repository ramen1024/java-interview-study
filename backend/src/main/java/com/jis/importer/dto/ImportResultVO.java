package com.jis.importer.dto;

import java.util.List;

/**
 * 一次内容导入的结果。
 *
 * <p>{@code errors} 非空表示有文件被跳过——必须让调用方看见，
 * 否则用户会以为内容已经生效，实际却少了几张卡片。
 */
public record ImportResultVO(

        int moduleCount,

        int cardCount,

        int followUpCount,

        int questionCount,

        int relationCount,

        long elapsedMs,

        List<String> warnings,

        List<String> errors
) {

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
