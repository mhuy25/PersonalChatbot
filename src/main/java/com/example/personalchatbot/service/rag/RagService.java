package com.example.personalchatbot.service.rag;

import com.example.personalchatbot.dto.AnswerDto;
import com.example.personalchatbot.dto.SearchHitDto;
import com.example.personalchatbot.service.implement.RagServiceImpl;
import com.example.personalchatbot.service.llm.LlmService;
import com.example.personalchatbot.service.prompt.PromptService;
import com.example.personalchatbot.service.search.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RagService implements RagServiceImpl {
    private final SearchService searchService;   // retrieve top-k từ pgvector
    private final PromptService promptService;   // dựng prompt từ hits
    private final LlmService llm;

    @Override
    public AnswerDto answer(String question,
                            int topK,
                            int keepN,
                            Map<String, String> filters,
                            double temperature,
                            Integer maxOutputTokens) {

        // 1) Retrieve: tìm top-k chunks theo câu hỏi (có thể có filters từ metadata)
        List<SearchHitDto> hits = searchService.search(
                question,
                topK <= 0 ? 60 : topK,   // mặc định 60 nếu không truyền
                filters == null ? Map.of() : filters // null-safe
        );

        // 2) (Optional) Rerank/keep-N: ở đây đơn giản là sort theo similarity giảm dần
        List<SearchHitDto> kept = hits.stream()
                .sorted(Comparator.comparingDouble(SearchHitDto::getSimilarity).reversed()) // sim cao trước
                .limit(keepN <= 0 ? 8 : keepN) // giữ N chunk tốt nhất (mặc định 8)
                .toList();

        // 3) Build prompt từ câu hỏi + context đã chọn
        var prompt = promptService.build(question, kept);

        // 4) Gọi LLM sinh câu trả lời
        String text = llm.generate(prompt.getSystem(), prompt.getUser(), temperature, maxOutputTokens);

        // 5) Gom danh sách citations để hiển thị cùng câu trả lời
        List<String> citations = kept.stream()
                .map(hit -> hit.getDocId() + "#" + hit.getChunkId())
                .toList();

        return new AnswerDto(text, citations);
    }
}
