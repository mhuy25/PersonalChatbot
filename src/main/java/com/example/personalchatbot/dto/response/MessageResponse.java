package com.example.personalchatbot.dto.response;

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MessageResponse extends APIResponseDto {
}
