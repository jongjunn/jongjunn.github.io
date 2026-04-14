package com.wanted.ailienlmsprogram.member.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignupRequest {
    private String loginId;
    private String email;
    private String password;
    private String name;
    private String phone;
}