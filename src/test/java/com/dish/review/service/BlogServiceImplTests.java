package com.dish.review.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dish.review.dto.Result;
import com.dish.review.entity.Blog;
import com.dish.review.mapper.BlogMapper;
import com.dish.review.service.impl.BlogServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证热门博客批量补作者时的空页边界。 */
class BlogServiceImplTests {

    private BlogMapper blogMapper;
    private IUserService userService;
    private BlogServiceImpl service;

    @BeforeEach
    void setUp() {
        blogMapper = mock(BlogMapper.class);
        userService = mock(IUserService.class);
        service = new BlogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", blogMapper);
        ReflectionTestUtils.setField(service, "userService", userService);
    }

    @Test
    void emptyHotPageReturnsWithoutEmptyBatchQuery() {
        when(blogMapper.selectPage(any(IPage.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Result result = service.queryHotBlog(999);

        assertTrue(result.getSuccess());
        assertEquals(Collections.emptyList(), result.getData());
        verify(userService, never()).listByIds(any());
    }
}
