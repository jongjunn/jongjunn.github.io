package com.wanted.ailienlmsprogram.member.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/access-denied")
    public String accessDeniedPage() {
        return "common/access-denied";
    }

    @GetMapping("/student")
    public String studentPage() {
        return "student/student-home";
    }

    @GetMapping("/instructor")
    public String instructorPage() {
        return "instructor/instructor-home";
    }

    @GetMapping("/admin")
    public String adminPage() {
        return "admin/admin-home";
    }
}