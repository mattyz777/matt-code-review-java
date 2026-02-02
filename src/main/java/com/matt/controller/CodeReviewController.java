package com.matt.controller;

import com.matt.service.GitService;
import lombok.AllArgsConstructor;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RequestMapping("code-review")
@RestController
@AllArgsConstructor
public class CodeReviewController {
    private final GitService gitService;

    @GetMapping
    public String codeReview() throws GitAPIException, IOException {
        return gitService.process(
                "C:\\code\\projs\\coinw\\matt-gbg",
                "main",
                "20260202-mr_1-matt");
    }
}
