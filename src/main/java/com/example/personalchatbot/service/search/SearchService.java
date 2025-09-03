package com.example.personalchatbot.service.search;

import com.example.personalchatbot.dto.SearchHitDto;
import com.example.personalchatbot.service.embedding.EmbeddingService;
import com.example.personalchatbot.service.implement.SearchServiceImpl;
import com.pgvector.PGvector;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.postgresql.util.PGobject;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class SearchService implements SearchServiceImpl {

    private final EmbeddingService embeddingService;

    @PersistenceContext
    private EntityManager entityManager;

    private static final Set<String> ALLOWED_META_KEYS = Set.of(
            "project", "module", "env", "title", "path"
    );

    @Override
    public List<SearchHitDto> search(String query, int k, Map<String, String> filters) {
        if (k <= 0) k = 10;

        // 1) Embed query -> PGvector
        PGvector qvec = embeddingService.embed(query);

        // 2) Chuẩn bị SQL (native) với cosine distance
        StringBuilder sql = new StringBuilder("""
            SELECT id, doc_id, chunk_id, content, metadata,
                   (embedding <=> CAST(:q AS vector)) AS distance
            FROM rag_chunks
            WHERE 1=1
            """);

        // 3) Thêm filter metadata động (metadata->>'key' = :meta_key)
        Map<String, Object> params = new HashMap<>();
        params.put("q", qvec.toString()); // dùng chuỗi "[...]" + CAST(:q AS vector)

        if (filters != null) {
            for (var e : filters.entrySet()) {
                String key = e.getKey();
                if (!ALLOWED_META_KEYS.contains(key)) continue; // bỏ qua key không whitelisted
                String paramName = "meta_" + key;
                sql.append(" AND (metadata->>'").append(key).append("') = :").append(paramName).append("\n");
                params.put(paramName, e.getValue());
            }
        }

        // 4) Order theo distance ASC (gần nhất trước)
        sql.append(" ORDER BY distance ASC");

        // 5) Tạo query & bind tham số
        Query q = entityManager.createNativeQuery(sql.toString());
        // LIMIT dùng setMaxResults để portable
        q.setMaxResults(k);

        for (var e : params.entrySet()) q.setParameter(e.getKey(), e.getValue());

        // 6) Thực thi và map về SearchHit
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();

        List<SearchHitDto> hits = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            // cột theo SELECT: id, doc_id, chunk_id, content, metadata, distance
            java.util.UUID id = (java.util.UUID) r[0];
            String docId = Objects.toString(r[1], null);
            long chunkId = ((Number) r[2]).longValue();
            String content = Objects.toString(r[3], null);
            String metadataJson = toJsonString(r[4]);
            double distance = ((Number) r[5]).doubleValue();
            double similarity = 1.0 - distance; // cosine similarity ~ 1 - distance

            hits.add(new SearchHitDto(id, docId, chunkId, content, metadataJson, distance, similarity));
        }
        return hits;
    }

    // Trả về JSON dưới dạng String, dù driver có thể trả PGobject(jsonb) hoặc String
    private static String toJsonString(Object o) {
        return switch (o) {
            case null -> null;
            case String s -> s;
            case PGobject pgo -> pgo.getValue();
            default -> Objects.toString(o);
        };
    }
}
