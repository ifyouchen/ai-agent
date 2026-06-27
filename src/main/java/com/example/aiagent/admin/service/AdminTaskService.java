package com.example.aiagent.admin.service;

import com.example.aiagent.story.mapper.GenerationTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminTaskService {

    private final GenerationTaskMapper generationTaskMapper;

    public Map<String, Object> listTasks(String status, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim();
        long total = normalizedStatus == null
                ? generationTaskMapper.countAll()
                : generationTaskMapper.countByStatus(normalizedStatus);
        return AdminPageResult.of(
                generationTaskMapper.findAdminPage(normalizedStatus, safePage * safeSize, safeSize),
                total,
                safePage,
                safeSize
        );
    }
}
