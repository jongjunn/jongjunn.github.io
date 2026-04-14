package com.wanted.ailienlmsprogram.home.controller;

import com.wanted.ailienlmsprogram.continent.service.ContinentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final ContinentService continentService;

    @GetMapping("/")
    public String home(@RequestParam(required = false) String query, Model model) {
        model.addAttribute("continents", continentService.searchContinents(query));
        model.addAttribute("query", query);
        return "home/index";
    }
}