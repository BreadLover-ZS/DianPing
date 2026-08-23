package com.dish.review.utils;

import com.dish.review.dto.UserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证管理写接口的匿名、普通用户和管理员授权边界。 */
class AdminInterceptorTests {

    private final AdminInterceptor interceptor = new AdminInterceptor();

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void readRequestRemainsPublic() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/shop/1");
        assertTrue(interceptor.preHandle(
                request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void anonymousAndNormalUserCannotWrite() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/shop");
        MockHttpServletResponse anonymousResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, anonymousResponse, new Object()));
        assertEquals(403, anonymousResponse.getStatus());

        UserDTO user = new UserDTO();
        user.setId(7L);
        user.setRole(SystemConstants.ROLE_USER);
        UserHolder.saveUser(user);
        MockHttpServletResponse userResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, userResponse, new Object()));
        assertEquals(403, userResponse.getStatus());
    }

    @Test
    void adminCanWrite() {
        UserDTO admin = new UserDTO();
        admin.setId(1L);
        admin.setRole(SystemConstants.ROLE_ADMIN);
        UserHolder.saveUser(admin);

        assertTrue(interceptor.preHandle(
                new MockHttpServletRequest("PUT", "/shop"),
                new MockHttpServletResponse(),
                new Object()));
    }
}
