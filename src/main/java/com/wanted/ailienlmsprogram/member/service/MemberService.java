package com.wanted.ailienlmsprogram.member.service;

import com.wanted.ailienlmsprogram.member.dto.SignupRequest;
import com.wanted.ailienlmsprogram.member.entity.Member;
import com.wanted.ailienlmsprogram.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public void signup(SignupRequest request) {
        if (memberRepository.existsByLoginId(request.getLoginId())) {
            throw new IllegalArgumentException("이미 사용 중인 아이디입니다.");
        }

        if (memberRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("이미 사용 중인 이메일입니다.");
        }

        Member member = new Member();
        member.setLoginId(request.getLoginId());
        member.setEmail(request.getEmail());
        member.setPassword(passwordEncoder.encode(request.getPassword()));
        member.setName(request.getName());
        member.setPhone(request.getPhone());
        member.setRole(Member.MemberRole.STUDENT);
        member.setAccountStatus(Member.AccountStatus.ACTIVE);

        // 정책 반영
        member.setRank(Member.MemberRank.REPTILIAN);

        member.setCreatedAt(LocalDateTime.now());
        member.setUpdatedAt(LocalDateTime.now());

        memberRepository.save(member);
    }

    @Transactional
    public void handleLoginSuccess(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("회원이 존재하지 않습니다."));

        LocalDateTime now = LocalDateTime.now();
        applyRankPolicy(member, now);

        member.setLastLoginAt(now);
        member.setUpdatedAt(now);
    }

    private void applyRankPolicy(Member member, LocalDateTime now) {
        LocalDateTime lastLoginAt = member.getLastLoginAt();

        // 첫 로그인 또는 이전 로그인 기록이 없으면 기본 등급 유지
        if (lastLoginAt == null) {
            return;
        }

        long inactiveDays = Duration.between(lastLoginAt, now).toDays();

        if (inactiveDays >= 7) {
            member.setRank(Member.MemberRank.NOVICE);
            return;
        }

        if (inactiveDays >= 3 && member.getRank() == Member.MemberRank.REPTILIAN) {
            member.setRank(Member.MemberRank.MINERVAL);
        }
    }
}