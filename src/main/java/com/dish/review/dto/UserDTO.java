package com.dish.review.dto;

import lombok.Data;

@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
    /** 仅供服务端鉴权使用；公开他人主页时由 Controller 清除。 */
    private String role;
}
