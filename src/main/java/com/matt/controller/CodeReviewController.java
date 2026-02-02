package com.matt.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("code-review")
@RestController
public class CodeReviewController {
    @GetMapping
    public String codeReview() {
        return "ok";
    }
}
