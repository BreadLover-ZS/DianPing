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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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

    /**
     * 允许上传的文件扩展名白名单
     * Java 8 兼容写法（Set.of 需 Java 9+），使用不可变 Set 保证线程安全
     */
    private static final Set<String> ALLOWED_EXTENSIONS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "bmp")));

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
     * 1. 只保留 DELETE 方法（移除 GET），符合 RESTful 规范，降低 CSRF 风险
     * 2. 路径穿越防护使用 {@link Path#startsWith(Path)} 组件级比较，
     *    消除字符串公共前缀漏洞（如上传目录 {@code /upload} 与 {@code /upload_backup}
     *    拥有公共前缀，字符串 startsWith 会误放行）
     * 3. 归一化 Windows 反斜杠：攻击者提交 {@code ..\} 在 Windows 部署下
     *    就是真实穿越，统一按 / 解析
     * 4. 文件已存在时核验真实路径（toRealPath），防止目录符号链接逃逸
     * 5. 检查删除结果：文件不存在或删除失败不返回成功
     *
     * @param filename 要删除的文件名
     * @return 操作结果
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        try {
            java.nio.file.Path uploadPath = java.nio.file.Paths
                    .get(SystemConstants.IMAGE_UPLOAD_DIR)
                    .toAbsolutePath()
                    .normalize();

            java.nio.file.Path targetPath =
                    resolveSafeTarget(uploadPath, filename);

            // 1. 路径穿越防护：拒绝目录本身、../ 和 ..\ 穿越、绝对路径注入
            if (targetPath == null) {
                log.warn("检测到路径穿越攻击：filename={}", filename);
                return Result.fail("非法的文件路径");
            }

            // 2. 校验不是目录
            if (java.nio.file.Files.isDirectory(targetPath)) {
                return Result.fail("错误的文件名称");
            }

            // 3. 文件不存在时不返回成功
            if (!java.nio.file.Files.exists(targetPath)) {
                return Result.fail("文件不存在");
            }

            // 4. 核验真实路径，防止目录符号链接逃逸
            if (!isRealPathWithinUpload(uploadPath, targetPath)) {
                log.warn("检测到符号链接逃逸：filename={}", filename);
                return Result.fail("非法的文件路径");
            }

            // 5. 删除文件并检查结果，失败不返回成功
            boolean deleted = FileUtil.del(targetPath.toFile());

            if (!deleted) {
                log.error("文件删除失败：filename={}", filename);
                return Result.fail("文件删除失败");
            }

            return Result.ok();
        } catch (IOException e) {
            log.error("文件删除失败", e);
            return Result.fail("文件删除失败");
        }
    }

    /**
     * 解析文件名并做词法级路径安全校验；非法返回 null。
     *
     * <p>归一化 Windows 反斜杠后用 {@link java.nio.file.Path#startsWith(Path)}
     * 做路径组件级比较（字符串公共前缀漏洞如 {@code /upload} 与
     * {@code /upload_backup} 会被误放行，组件级比较不会）。
     * 拒绝：目录本身、{@code ../} 穿越、{@code ..\} 穿越（归一化后）、
     * 绝对路径注入。public static 以便单元测试直接覆盖穿越矩阵。</p>
     *
     * @param uploadPath 已规范化的上传目录绝对路径
     * @param filename 用户提交的文件名
     * @return 安全的目标文件路径；非法返回 null
     */
    public static java.nio.file.Path resolveSafeTarget(
            java.nio.file.Path uploadPath, String filename) {

        if (uploadPath == null || filename == null) {
            return null;
        }

        // 归一化 Windows 分隔符：攻击者提交 ..\ 在 Windows 部署下
        // 就是真实穿越，统一按 / 解析
        String normalizedName = filename.replace('\\', '/');

        java.nio.file.Path targetPath = uploadPath
                .resolve(normalizedName)
                .toAbsolutePath()
                .normalize();

        if (targetPath.equals(uploadPath)
                || !targetPath.startsWith(uploadPath)) {
            return null;
        }

        return targetPath;
    }

    /**
     * 核验目标文件的真实路径（解析符号链接后）仍在上传目录内。
     *
     * <p>词法校验通过后，文件已存在时还必须核验真实路径：
     * 上传目录内的符号链接可能指向目录外，形成逃逸。</p>
     */
    public static boolean isRealPathWithinUpload(
            java.nio.file.Path uploadPath,
            java.nio.file.Path targetPath) throws IOException {

        return targetPath.toRealPath()
                .startsWith(uploadPath.toRealPath());
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
