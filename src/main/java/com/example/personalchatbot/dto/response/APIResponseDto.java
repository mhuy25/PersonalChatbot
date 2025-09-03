package com.example.personalchatbot.dto.response;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@EqualsAndHashCode(callSuper = false)
@Data
@SuperBuilder
@FieldDefaults(level = AccessLevel.PROTECTED)
public abstract class APIResponseDto {
    String path;
    String status;
    @Builder.Default
    LocalDateTime timestamp = LocalDateTime.now();
    String message;
}
