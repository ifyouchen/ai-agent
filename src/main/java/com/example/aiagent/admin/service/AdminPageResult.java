package com.example.aiagent.admin.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AdminPageResult {

    private AdminPageResult() {
    }

    public static Map<String, Object> of(List<Map<String, Object>> items,
                                         long total,
                                         int page,
                                         int size) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", size <= 0 ? 0 : (int) Math.ceil((double) total / size));
        return result;
    }
}
