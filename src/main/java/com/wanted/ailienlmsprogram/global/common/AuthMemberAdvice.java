package com.wanted.ailienlmsprogram.global.common;

import com.wanted.ailienlmsprogram.global.security.CustomUserDetails;
import com.wanted.ailienlmsprogram.member.entity.Member;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class AuthMemberAdvice {

    @ModelAttribute("loginMember")
    public Member loginMember(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return userDetails != null ? userDetails.getMember() : null;
    }
}