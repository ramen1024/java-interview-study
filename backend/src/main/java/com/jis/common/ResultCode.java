package com.jis.common;

import lombok.Getter;

/**
 * 统一业务状态码。
 *
 * <p>码段划分：0 成功，4xx/5xx 对齐 HTTP 语义，1xxx 用户与认证，2xxx 内容，3xxx 复习与答题。
 */
@Getter
public enum ResultCode {

    SUCCESS(0, "操作成功"),

    BAD_REQUEST(400, "请求参数有误"),
    UNAUTHORIZED(401, "登录状态已失效，请重新登录"),
    FORBIDDEN(403, "没有访问权限"),
    NOT_FOUND(404, "资源不存在"),

    INTERNAL_ERROR(500, "服务器内部错误"),

    USERNAME_EXISTS(1001, "该用户名已被注册"),
    USER_NOT_FOUND(1002, "用户不存在"),
    PASSWORD_INCORRECT(1003, "用户名或密码错误"),
    TOKEN_INVALID(1004, "凭证无效或已过期"),
    USER_DISABLED(1005, "账号已被禁用"),

    KNOWLEDGE_POINT_NOT_FOUND(2001, "知识点不存在"),
    MODULE_NOT_FOUND(2002, "模块不存在"),
    IMPORT_IN_PROGRESS(2004, "已有导入任务正在执行，请稍后再试"),

    REVIEW_STATE_NOT_FOUND(3001, "复习记录不存在"),
    RATING_INVALID(3002, "评分取值不合法"),
    QUESTION_NOT_FOUND(3003, "题目不存在");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
