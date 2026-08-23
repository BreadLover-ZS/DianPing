package com.dish.review.utils;

public class SystemConstants {
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;

    /** 统一把分页页码限制为正数，避免负页码和空页码进入 SQL。 */
    public static int normalizePage(Integer page) {
        return page == null ? 1 : Math.max(1, page);
    }
}
