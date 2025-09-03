package com.example.personalchatbot.service.embedding;

import com.example.personalchatbot.config.ErrorConfig;
import com.example.personalchatbot.dto.EmbeddingDto;
import com.example.personalchatbot.dto.request.EmbeddingRequest;
import com.example.personalchatbot.dto.response.EmbeddingResponse;
import com.example.personalchatbot.entity.ChunkMessage;
import com.example.personalchatbot.exception.AppException;
import com.example.personalchatbot.repository.RagChunkRepository;
import com.example.personalchatbot.service.implement.EmbeddingServiceImpl;
import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService implements EmbeddingServiceImpl {
    private final WebClient embeddingWebClient;
    private final EmbeddingDto embeddingDto;
    private final RagChunkRepository ragChunkRepo;

    @Override
    public PGvector embed(String text) {
        List<PGvector> embeddedList = embedAll(List.of(text));
        if (embeddedList.isEmpty()) {
            throw new AppException(ErrorConfig.NO_DATA_FOUND, "Empty embedding response");
        }
        return embeddedList.getFirst();
    }

    @Override
    public List<PGvector> embedAll(List<String> texts) {
        try {
            // batch size cấu hình (mặc định 64 nếu null/<=0)
            final int bs = embeddingDto.getBatchSize() != null && embeddingDto.getBatchSize() > 0 ? embeddingDto.getBatchSize() : 64;

            List<PGvector> result = new ArrayList<>(texts.size());
            // chia nhỏ input theo batch
            for (int i = 0; i < texts.size(); i += bs) {
                List<String> batch = texts.subList(i, Math.min(i + bs, texts.size()));
                // gọi provider và ghép vào kết quả chung
                result.addAll(callOpenAIEmbeddings(batch));
            }
            if (result.size() != texts.size()) {
                throw new AppException(
                        ErrorConfig.INTERNAL_SERVER_ERROR,
                        "Embedding size mismatch: expected %d, got %d".formatted(texts.size(), result.size()));
            }
            return result;
        } catch (Exception e) {
            throw new AppException(ErrorConfig.INTERNAL_SERVER_ERROR,e.getMessage());
        }
    }

    /** Tạo embedding cho danh sách chunk và set vào field `embedding` (PGvector) */
    @Override
    public List<ChunkMessage> embedAndAttach(List<ChunkMessage> chunks) {
        try {
            // gom nội dung theo đúng thứ tự
            List<String> inputs = chunks.stream().map(ChunkMessage::getContent).toList();
            // gọi embed
            List<PGvector> vectors = embedAll(inputs);
            // gán PGvector vào entity theo vị trí tương ứng
            for (int i = 0; i < chunks.size(); i++) {
                chunks.get(i).setEmbedding(vectors.get(i));
            }
            return chunks;
        } catch (Exception e) {
            throw new AppException(ErrorConfig.INTERNAL_SERVER_ERROR,e.getMessage());
        }
    }

    /** Tạo embedding và lưu toàn bộ vào DB */
    @Override
    public void embedAndSaveAll(List<ChunkMessage> chunks) {
        try {
            embedAndAttach(chunks);
            ragChunkRepo.saveAll(chunks);
        } catch (Exception e) {
            throw new AppException(ErrorConfig.INTERNAL_SERVER_ERROR,e.getMessage());
        }
    }

    /**
     * Gọi POST /embeddings với body: { model, input[], encoding_format:"float" }
     * Trả về danh sách PGvector (đã chuẩn hoá L2 nếu props.normalize=true).
     */
    private List<PGvector> callOpenAIEmbeddings(List<String> batch) {
        try {
            // Request body (đặt encoding_format="float" để nhận mảng số thực)
            var body = new EmbeddingRequest(
                    embeddingDto.getModel(),
                    batch,
                    "float"
            );

            // Thực hiện HTTP POST, retry nhẹ khi 429/5xx
            var resp = embeddingWebClient.post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(s -> s.is4xxClientError() && s.value() != 429, r ->
                            r.bodyToMono(String.class).flatMap(err -> {
                                log.error("Embeddings 4xx NON-RETRY: {}", err);
                                return Mono.error(new WebClientResponseException(
                                        "4xx from embeddings: " + err,
                                        r.statusCode().value(), r.statusCode().toString(),
                                        null, null, null
                                ));
                            })
                    )
                    .onStatus(s -> s.is5xxServerError() || s.value() == 429, r ->
                            r.bodyToMono(String.class).flatMap(err -> {
                                log.warn("Embeddings RETRYABLE {}: {}", r.statusCode(), err);
                                return Mono.error(new WebClientResponseException(
                                        "retryable from embeddings: " + err,
                                        r.statusCode().value(), r.statusCode().toString(),
                                        null, null, null
                                ));
                            })
                    )
                    .bodyToMono(EmbeddingResponse.class)
                    .retryWhen(
                            Retry.backoff(3, Duration.ofMillis(500))
                                    .jitter(0.2)
                                    .filter(ex -> ex instanceof WebClientResponseException wex &&
                                            (wex.getStatusCode().is5xxServerError() || wex.getStatusCode().value() == 429))
                                    .onRetryExhaustedThrow((spec, signal) ->
                                            new AppException(
                                                    ErrorConfig.INTERNAL_SERVER_ERROR,
                                                    "Retries exhausted: " + (signal.totalRetries() + 1)
                                                            + " / last: " + signal.failure().getMessage()
                                            )
                                    )
                    )
                    .block();

            if (resp == null || resp.getDataList() == null || resp.getDataList().isEmpty()) {
                throw new IllegalStateException("Empty embedding response");
            }

            Integer expect = embeddingDto.getExpectedDim();            // ví dụ 1536
            boolean norm = embeddingDto.getNormalize() == null || embeddingDto.getNormalize();

            // Sắp xếp theo index để bảo toàn thứ tự input -> output
            resp.getDataList().sort(Comparator.comparingInt(EmbeddingResponse.DataEmbedding::getIndex));

            List<PGvector> out = new ArrayList<>(resp.getDataList().size());
            for (var d : resp.getDataList()) {
                // chuyển List<Double> -> float[]
                float[] vec = toFloatArray(d.getEmbedding());
                // kiểm tra chiều vector nếu cấu hình expectDim
                if (expect != null && vec.length != expect) {
                    throw new IllegalStateException("Embedding dim mismatch: " + vec.length + " != " + expect);
                }
                // tuỳ chọn chuẩn hoá L2 để dùng cosine ổn định
                if (norm) l2NormalizeInPlace(vec);
                // tạo PGvector từ mảng float và add vào danh sách
                out.add(new PGvector(vec));
            }
            return out;
        } catch (Exception e) {
            throw new AppException(ErrorConfig.INTERNAL_SERVER_ERROR,e.getMessage());
        }
    }

    /** Chuyển List<Double> -> float[] để tạo PGvector */
    private float[] toFloatArray(List<Double> doubles) {
        float[] f = new float[doubles.size()];
        for (int i = 0; i < doubles.size(); i++) f[i] = doubles.get(i).floatValue();
        return f;
    }

    /** Chuẩn hoá L2 ngay trên mảng (v/||v||) */
    private void l2NormalizeInPlace(float[] v) {
        double sum = 0.0;
        for (float x : v) sum += (double) x * x;
        double norm = Math.sqrt(sum);
        if (norm == 0.0) return;
        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / norm);
    }
}
