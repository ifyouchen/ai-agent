package com.example.aiagent.admin.controller;

import com.example.aiagent.admin.service.AdminKnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminKnowledgeController {

    private final AdminKnowledgeService adminKnowledgeService;

    @GetMapping("/kbs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> knowledgeBases(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String tenantId,
            @RequestParam(required = false) Integer status) {
        return ResponseEntity.ok(adminKnowledgeService
                .listKnowledgeBases(keyword, tenantId, status, page, size));
    }

    @GetMapping("/documents")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> documents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String parseStatus,
            @RequestParam(required = false) Long kbId,
            @RequestParam(defaultValue = "") String tenantId) {
        return ResponseEntity.ok(adminKnowledgeService
                .listDocuments(keyword, parseStatus, kbId, tenantId, page, size));
    }
}
