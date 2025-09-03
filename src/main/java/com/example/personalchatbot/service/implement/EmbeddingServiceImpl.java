package com.example.personalchatbot.service.implement;

import com.example.personalchatbot.entity.ChunkMessage;
import com.pgvector.PGvector;

import java.util.List;

public interface EmbeddingServiceImpl {
    PGvector embed(String text);                         // 1 text -> 1 vector
    List<PGvector> embedAll(List<String> texts);         // batch
    List<ChunkMessage> embedAndAttach(List<ChunkMessage> chunks); // gán vào entity
    void embedAndSaveAll(List<ChunkMessage> chunks);    // gán & saveAll()
}
