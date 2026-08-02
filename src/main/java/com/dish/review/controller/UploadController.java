package com.dish.review.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.dish.review.dto.Result;
import com.dish.review.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * 文件上传控制器
 *
 * 【安全修复 Fix 3-4】
 * 1. 添加文件类型白名单校验，仅允许图片格式上传，防止上传可执行文件（WebShell）
 * 2. 添加文件大小限制（5MB），防止大文件上传导致资源耗尽
 * 3. 文件删除接口增加路径穿越防护，通过 canonical path 比较确保目标文件在上传目录内
 * 4. 文件删除接口改为支持 DELETE 方法，符合 RESTful 规范，降低 CSRF 风险
 */
@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    /** 允许上传的文件扩展名白名单 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");

    /** 最大文件大小：5MB */
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    /**
     * 上传博客图片
     *
     * 【安全修复 Fix 3】新增文件类型白名单校验和大小限制，防止上传恶意可执行文件
     *
     * @param image 用户上传的图片文件
     * @return 生成的文件名
     */
    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            // 1. 校验文件是否为空
            if (image.isEmpty()) {
                return Result.fail("文件不能为空");
            }

            // 2. 校验文件大小
            if (image.getSize() > MAX_FILE_SIZE) {
                return Result.fail("文件大小不能超过5MB");
            }

            // 3. 获取原始文件名称并校验扩展名
            String originalFilename = image.getOriginalFilename();
            if (StrUtil.isBlank(originalFilename)) {
                return Result.fail("文件名不能为空");
            }

            // 4. 校验文件类型是否在白名单中
            String suffix = StrUtil.subAfter(originalFilename, ".", true);
            if (StrUtil.isBlank(suffix) || !ALLOWED_EXTENSIONS.contains(suffix.toLowerCase())) {
                return Result.fail("仅支持 jpg, jpeg, png, gif, webp, bmp 格式的图片");
            }

            // 5. 生成新文件名并保存
            String fileName = createNewFileName(originalFilename);
            image.transferTo(new File(SystemConstants.IMAGE_UPLOAD_DIR, fileName));
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    /**
     * 删除博客图片
     *
     * 【安全修复 Fix 4】
     * 1. 改用 DELETE 方法（同时兼容 GET），符合 RESTful 规范
     * 2. 增加路径穿越防护：通过 canonical path 比较确保目标文件在上传目录内
     *
     * @param filename 要删除的文件名
     * @return 操作结果
     */
    @RequestMapping(value = "/blog/delete", method = {RequestMethod.DELETE, RequestMethod.GET})
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        try {
            // 1. 获取上传目录的规范化路径
            File uploadDir = new File(SystemConstants.IMAGE_UPLOAD_DIR).getCanonicalFile();

            // 2. 构建目标文件并获取规范化路径
            File file = new File(uploadDir, filename).getCanonicalFile();

            // 3. 路径穿越防护：校验目标文件路径是否在上传目录内
            if (!file.getPath().startsWith(uploadDir.getPath())) {
                log.warn("检测到路径穿越攻击：filename={}", filename);
                return Result.fail("非法的文件路径");
            }

            // 4. 校验不是目录
            if (file.isDirectory()) {
                return Result.fail("错误的文件名称");
            }

            // 5. 删除文件
            FileUtil.del(file);
            return Result.ok();
        } catch (IOException e) {
            log.error("文件删除失败", e);
            return Result.fail("文件删除失败");
        }
    }

    /**
     * 根据原始文件名生成新的存储文件名
     * 使用 UUID + hash 二级目录结构，避免文件名冲突
     *
     * @param originalFilename 原始文件名
     * @return 新的文件存储路径
     */
    private String createNewFileName(String originalFilename) {
        // 获取后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 判断目录是否存在
        File dir = new File(SystemConstants.IMAGE_UPLOAD_DIR, StrUtil.format("/blogs/{}/{}", d1, d2));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 生成文件名
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}
