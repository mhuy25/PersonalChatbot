package com.example.personalchatbot.service.llm;

import com.example.personalchatbot.config.ErrorConfig;
import com.example.personalchatbot.config.LlmConfig;
import com.example.personalchatbot.exception.AppException;
import com.example.personalchatbot.service.implement.LlmServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService implements LlmServiceImpl {
    private final WebClient embeddingWebClient;
    private final LlmConfig llmConfig;

    @Override
    public String generate(String system, String user, double temperature, Integer maxOutputTokens) {
        // Body cho Responses API: gồm model, nhiệt, giới hạn output, và mảng input messages
        var body = Map.of(
                "model", llmConfig.getModel(),
                "temperature", llmConfig.getTemperature(),
                "max_output_tokens", llmConfig.getOutputToken(),
                "input", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)
                )
        );

        // Gọi POST /responses
        var resp = embeddingWebClient.post()
                .uri("/responses") // endpoint Responses API
                .contentType(MediaType.APPLICATION_JSON) // gửi JSON
                .bodyValue(body) // nhét body
                .retrieve() // thực thi
                .onStatus(HttpStatusCode::isError, errorResp ->
                        errorResp.bodyToMono(String.class).flatMap(err -> {
                            log.error("OpenAI {}: {}", errorResp.statusCode(), err); // <-- xem message cụ thể
                            return Mono.error(new RuntimeException(err));
                        })
                )
                .bodyToMono(Map.class) // nhận về Map để bóc thủ công
                .block(); // chờ kết quả sync

        if (resp == null) {
            throw new AppException(ErrorConfig.INTERNAL_SERVER_ERROR, "Empty LLM response");
        }

        // Bóc text chuẩn: output[0].content[0].text
        Object output = resp.get("output"); // lấy trường output
        if (!(output instanceof List<?> out) || out.isEmpty()) return "";
        Object first = out.get(0); // phần tử đầu
        if (!(first instanceof Map<?,?> m)) return "";
        Object content = m.get("content"); // mảng content
        if (!(content instanceof List<?> cl) || cl.isEmpty()) return "";
        Object firstContent = cl.get(0); // phần tử đầu content
        if (!(firstContent instanceof Map<?,?> cm)) return "";
        Object text = cm.get("text"); // text trả lời
        return text == null ? "" : text.toString(); // trả chuỗi (có thể rỗng)
    }
}
