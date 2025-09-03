package com.example.personalchatbot.service.implement;

import com.example.personalchatbot.dto.SearchHitDto;

import java.util.List;
import java.util.Map;

public interface SearchServiceImpl {

    /**
     * Tìm top-k chunk theo truy vấn text (sẽ embed query)
     * @param query   câu hỏi/từ khoá
     * @param k       số kết quả tối đa
     * @param filters lọc metadata (vd: {"project":"Finex","env":"UAT"})
     */
    List<SearchHitDto> search(String query, int k, Map<String, String> filters);
}
