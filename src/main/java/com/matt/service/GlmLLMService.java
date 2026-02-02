package com.matt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GlmLLMService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${glm.api.url:https://open.bigmodel.cn/api/paas/v4/chat/completions}")
    private String apiUrl;

    @Value("${glm.api.key}")
    private String apiKey;

    @Value("${glm.model:glm-4.7}")
    private String model;

    public String review(String structuredDiffPayload) {
        try {
            String prompt = buildUserPrompt(structuredDiffPayload);
            Map<String, Object> request = buildRequest(prompt);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<String> resp = restTemplate.exchange(
                    apiUrl,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            return extractAssistantContent(resp.getBody());
        } catch (Exception e) {
            throw new RuntimeException("LLM review failed", e);
        }
    }

    private Map<String, Object> buildRequest(String userPrompt) {
        return Map.of(
                "model", model,
                "temperature", 0.1,
                "max_tokens", 2048,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt()),
                        Map.of("role", "user", "content", userPrompt)
                )
        );
    }

    private String buildUserPrompt(String payloadJson) {
        return """
                Review the following changed code blocks:

                %s

                Return your review strictly in the following JSON format:

                {
                  "summary": "...",
                  "issues": [
                    {
                      "file": "...",
                      "type": "bug | security | performance | correctness | maintainability",
                      "severity": "low | medium | high | critical",
                      "location": "...",
                      "message": "...",
                      "suggestion": "..."
                    }
                  ]
                }
                """.formatted(payloadJson);
    }

    private String systemPrompt() {
        return """
                You are a senior backend engineer and static analysis reviewer.

                You will receive structured Java code blocks extracted from Git diffs.
                Each block represents a complete semantic unit (method, field, import, annotation).

                Your task:
                1. Review for:
                   - Bugs
                   - Logic errors
                   - Concurrency issues
                   - Security risks
                   - API misuse
                   - Performance problems
                   - Maintainability issues

                2. DO NOT:
                   - Comment on formatting or style unless it affects correctness
                   - Suggest renaming unless meaning is unclear
                   - Request unrelated refactors

                3. Output MUST be valid JSON in the schema requested.
                If no issues are found, return:
                {
                  "summary": "No issues found.",
                  "issues": []
                }
                """;
    }

    // ---------------- response parsing ----------------

    private String extractAssistantContent(String rawResponse) throws Exception {
        // GLM response format:
        // { choices: [ { message: { role: "assistant", content: "..." } } ] }
        Map<?, ?> root = MAPPER.readValue(rawResponse, Map.class);
        List<?> choices = (List<?>) root.get("choices");
        Map<?, ?> msg = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
        return msg.get("content").toString();
    }
}
