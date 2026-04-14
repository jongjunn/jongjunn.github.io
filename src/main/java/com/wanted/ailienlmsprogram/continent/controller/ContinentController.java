package com.wanted.ailienlmsprogram.continent.controller;

import com.wanted.ailienlmsprogram.continent.service.ContinentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/continents")
@RequiredArgsConstructor
public class ContinentController {

    private final ContinentService continentService;

    @GetMapping
    public String continentList(Model model) {
        model.addAttribute("continents", continentService.findAllContinents());
        return "home/index";
    }

    @GetMapping("/{continentId}")
    public String continentDetail(@PathVariable Long continentId, Model model) {
        model.addAttribute("detail", continentService.findContinentDetail(continentId));
        return "continent/detail";
    }
}