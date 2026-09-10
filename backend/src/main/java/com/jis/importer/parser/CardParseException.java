package com.jis.importer.parser;

/**
 * 内容文件结构不合法。携带文件名，便于导入报告直接指出问题所在。
 *
 * <p>这类错误是"内容作者的错"，不是缺陷，因此不打印堆栈。
 */
public class CardParseException extends RuntimeException {

    public CardParseException(String sourcePath, String message) {
        super("[" + sourcePath + "] " + message);
    }
}
