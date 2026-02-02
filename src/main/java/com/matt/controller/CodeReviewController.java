package com.matt.controller;

import com.matt.service.GitService;
import com.matt.service.GlmLLMService;
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
    private final GlmLLMService glmLLMService;

    private final static String REPO_PATH = "C:\\code\\projs\\coinw\\matt-gbg";
    private final static String TARGET_BRANCH = "main";
    private final static String SOURCE_BRANCH = "20260202-mr_1-matt";

    @GetMapping
    public String codeReview() throws GitAPIException, IOException {
        String changes =  gitService.process(REPO_PATH, TARGET_BRANCH, SOURCE_BRANCH);
        return glmLLMService.review(changes);
    }
}
