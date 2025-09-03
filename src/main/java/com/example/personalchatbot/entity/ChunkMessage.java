package com.example.personalchatbot.entity;

import com.pgvector.PGvector;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "rag_chunks")
public class ChunkMessage {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String docId;

    @Column(nullable = false)
    private long chunkId;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.OTHER)
    @Column(nullable = false, columnDefinition = "vector(1536)")
    private PGvector embedding;

    @Column(columnDefinition = "jsonb")
    private String metadata;

    private LocalDateTime updatedAt;
}
