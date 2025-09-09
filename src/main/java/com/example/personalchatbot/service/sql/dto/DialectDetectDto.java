package com.example.personalchatbot.service.sql.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.Map;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class DialectDetectDto {
    String dbType;                 // tên enum DbType (vd: "postgresql", "mysql", ...)
    double confidence;             // 0..1
    List<String> hits;             // các dấu hiệu khớp
    Map<String, Integer> debugScores; // điểm của từng ứng viên (for debug)
}
