package com.dish.review.controller;

import com.dish.review.dto.Result;
import com.dish.review.dto.UserDTO;
import com.dish.review.utils.SystemConstants;
import com.dish.review.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证上传魔数、用户目录隔离和删除所有权。 */
class UploadControllerTests {

    @TempDir
    Path imageDir;

    private UploadController controller;

    @BeforeEach
    void setUp() {
        controller = new UploadController();
        ReflectionTestUtils.setField(
                controller, "imageUploadDir", imageDir.toString());
        loginAs(7L, SystemConstants.ROLE_USER);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void validPngIsStoredUnderCurrentUserDirectory() {
        byte[] png = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        Result result = controller.uploadImage(
                new MockMultipartFile("file", "photo.png", "image/png", png));

        assertTrue(result.getSuccess());
        String relative = result.getData().toString().replaceFirst("^/+", "");
        assertTrue(relative.startsWith("blogs/7/"));
        assertTrue(Files.exists(imageDir.resolve(relative)));
    }

    @Test
    void disguisedImageIsRejected() {
        Result result = controller.uploadImage(new MockMultipartFile(
                "file", "shell.jpg", "image/jpeg", "not-image".getBytes()));

        assertFalse(result.getSuccess());
    }

    @Test
    void normalUserCannotDeleteAnotherUsersFile() throws Exception {
        Path other = imageDir.resolve("blogs/8/1/1/other.jpg");
        Files.createDirectories(other.getParent());
        Files.write(other, new byte[]{1});

        Result result = controller.deleteBlogImg(
                "/blogs/8/1/1/other.jpg");

        assertFalse(result.getSuccess());
        assertTrue(Files.exists(other));
    }

    private void loginAs(Long userId, String role) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        user.setRole(role);
        UserHolder.saveUser(user);
    }
}
