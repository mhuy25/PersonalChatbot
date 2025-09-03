package com.example.personalchatbot.service.prompt;

import com.example.personalchatbot.dto.PromptDto;
import com.example.personalchatbot.dto.SearchHitDto;
import com.example.personalchatbot.service.implement.PromptServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class PromptService implements PromptServiceImpl {
    private static final int MAX_CONTEXT_CHARS = 12000; // tuỳ ý chỉnh theo ngân sách token

    @Override
    public PromptDto build(String question, List<SearchHitDto> hits) {
        // Ghép các hit thành 1 chuỗi context, mỗi hit có tiêu đề nguồn + nội dung
        String context = (hits == null || hits.isEmpty())
                ? "(không có ngữ cảnh phù hợp)" : hits.stream()
                .map(this::formatHit) // format mỗi hit
                .collect(Collectors.joining("\n\n")) // ngăn cách bằng dòng trống
                // cắt bớt nếu vượt giới hạn (để không vỡ token limit)
                .substring(0, Math.min(MAX_CONTEXT_CHARS,
                        Math.max(0, hits.stream().map(this::formatHit)
                                .collect(Collectors.joining("\n\n")).length())));

        // System prompt: quy tắc trả lời
        String system = String.join("\n",
                "Bạn là trợ lý RAG nói tiếng Việt. Chỉ trả lời dựa trên NGỮ CẢNH được cung cấp.",
                "- Khi dùng thông tin, nhớ trích dẫn dạng [docId#chunkId].",
                "- Câu trả lời ngắn gọn, đúng trọng tâm."
        );

        // User prompt: gồm câu hỏi + phần context
        String user = """
                CÂU HỎI:
                %s

                NGỮ CẢNH (có thể dùng một phần hoặc toàn bộ):
                %s
                """.formatted(question, context);

        return new PromptDto(system, user);
    }

    // Hiển thị 1 kết quả với tiêu đề nguồn và nội dung
    private String formatHit(SearchHitDto hit) {
        // Tiêu đề nguồn có similarity để người đọc dễ đánh giá
        String header = "[%s#%d] (sim=%.3f)".formatted(hit.getDocId(), hit.getChunkId(), hit.getSimilarity());
        // Nội dung: giữ nguyên, có thể cắt bớt nếu bạn muốn
        return header + "\n" + hit.getContent();
    }
}
