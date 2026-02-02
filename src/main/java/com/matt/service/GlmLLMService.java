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

            log.info("calling llm api");
            ResponseEntity<String> resp = restTemplate.exchange(
                    apiUrl,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            String result = extractAssistantContent(resp.getBody());
            log.info("LLM review result: {}", result);
            return result;
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

    private String systemPrompt() {
        return """
        You are a code review engine.

        You MUST return a single valid JSON object and NOTHING ELSE.
        Do not include explanations, markdown, or code fences.
        If the output is not valid JSON, it will be rejected.

        Follow exactly the schema provided by the user.
        """;
    }

    private String buildUserPrompt(String payloadJson) {
        return """
        Review the following changed code blocks:

        %s

        Return ONLY a JSON object in this schema:

        {
          "summary": {
            "critical": number,
            "high": number,
            "medium": number,
            "low": number
          },
          "issues": [
            {
              "summary": string,
              "file": string,
              "type": "bug | security | performance | correctness | maintainability",
              "severity": "low | medium | high | critical",
              "location": string,
              "message": string,
              "suggestion": string
            }
          ]
        }

        Rules:
        - Output must be valid JSON.
        - Do not include markdown, comments, or extra text.
        - If no issues exist, return:

        {
          "summary": { "critical": 0, "high": 0, "medium": 0, "low": 0 },
          "issues": []
        }
        """.formatted(payloadJson);
    }

    private String extractAssistantContent(String rawResponse) throws Exception {
        // GLM response format:
        // { choices: [ { message: { role: "assistant", content: "..." } } ] }
        Map<?, ?> root = MAPPER.readValue(rawResponse, Map.class);
        List<?> choices = (List<?>) root.get("choices");
        Map<?, ?> msg = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
        return msg.get("content").toString();
    }
}
