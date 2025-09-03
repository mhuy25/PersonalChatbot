package com.example.personalchatbot.service.implement;

import com.example.personalchatbot.dto.ChunkingOptions;
import com.example.personalchatbot.dto.MetadataDto;
import com.example.personalchatbot.entity.ChunkMessage;


import java.util.List;

public interface ChunkServiceImpl {
    List<ChunkMessage> chunkText(String docId, String text, ChunkingOptions options, MetadataDto metadataDto);
    List<ChunkMessage> chunkJavaSource(String docId, String javaSource, MetadataDto metadataDto);
}
