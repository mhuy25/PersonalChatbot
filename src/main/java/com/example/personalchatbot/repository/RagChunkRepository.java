package com.example.personalchatbot.repository;

import com.example.personalchatbot.entity.ChunkMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RagChunkRepository extends JpaRepository<ChunkMessage, Long> {
    Optional<ChunkMessage> findByDocIdAndChunkId(String docId, Long chunkId);
}
