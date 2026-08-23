-- 评论、用户主页和热门博客的常用查询索引。
-- 执行前请用 SHOW INDEX 检查是否已有同名索引，避免重复创建。
ALTER TABLE tb_blog_comments
    ADD KEY idx_blog_comments_query (blog_id, status, create_time, id);

ALTER TABLE tb_blog
    ADD KEY idx_blog_user (user_id, id),
    ADD KEY idx_blog_liked (liked, id);
