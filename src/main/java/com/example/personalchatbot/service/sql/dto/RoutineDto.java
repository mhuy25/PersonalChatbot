package com.example.personalchatbot.service.sql.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RoutineDto {
    String qualifiedName;
    String dollarBody;
    String kind; // CREATE_FUNCTION | CREATE_PROCEDURE
}
