package com.jis.common;

/**
 * 当前登录用户的线程上下文。
 *
 * <p>由 {@code AuthInterceptor} 在请求进入时写入、请求结束时清理。
 * 清理必须放在 {@code afterCompletion}，否则线程复用会造成用户身份串号。
 */
public final class UserContext {

    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUserId(Long userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static Long getUserId() {
        return CURRENT_USER_ID.get();
    }

    /**
     * 取当前用户 ID，未登录直接抛 401。
     * 业务层可放心调用，不必到处判空。
     */
    public static Long requireUserId() {
        Long userId = CURRENT_USER_ID.get();
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }

        return userId;
    }

    public static void clear() {
        CURRENT_USER_ID.remove();
    }
}
