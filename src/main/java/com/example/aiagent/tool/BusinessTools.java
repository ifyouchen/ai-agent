package com.example.aiagent.tool;

import com.example.aiagent.kb.entity.Document;
import com.example.aiagent.kb.entity.KnowledgeBase;
import com.example.aiagent.kb.mapper.DocumentMapper;
import com.example.aiagent.kb.service.KnowledgeBaseService;
import com.example.aiagent.kb.service.KbMemberService;
import com.example.aiagent.security.service.OrganizationService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessTools {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private final OrganizationService organizationService;
    private final KnowledgeBaseService kbService;
    private final KbMemberService kbMemberService;
    private final DocumentMapper documentMapper;

    // ── 1. 组织查询 ──────────────────────────────────────────

    @Tool("列出当前用户加入的所有组织，包括组织名称、类型（个人/企业）、用户在组织中的角色")
    public String listMyOrganizations() {
        log.info("[Tool] 查询我的组织");
        try {
            String userId = getCurrentUserId();
            if (userId == null) {
                return "无法识别当前用户身份，请先登录。";
            }

            List<Map<String, Object>> orgs = organizationService.getUserOrganizationsWithDetail(userId);
            if (orgs.isEmpty()) {
                return "您当前尚未加入任何组织。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("您共加入了 %d 个组织：\n\n", orgs.size()));

            for (int i = 0; i < orgs.size(); i++) {
                Map<String, Object> org = orgs.get(i);
                String orgId = (String) org.get("orgId");
                String name = (String) org.get("name");
                String orgType = (String) org.get("orgType");
                String role = (String) org.get("role");

                String typeLabel = "PERSONAL".equals(orgType) ? "个人空间" : "企业组织";
                String roleLabel = switch (role) {
                    case "OWNER" -> "拥有者";
                    case "ADMIN" -> "管理员";
                    case "MEMBER" -> "成员";
                    default -> role;
                };

                sb.append(String.format("%d. %s（%s）\n", i + 1, name, typeLabel));
                sb.append(String.format("   组织ID：%s  角色：%s\n", orgId, roleLabel));

                try {
                    List<Map<String, Object>> members = organizationService.getOrgMembersWithUsername(orgId);
                    sb.append(String.format("   成员数：%d 人\n", members.size()));
                } catch (Exception ignored) {
                }
                sb.append("\n");
            }
            sb.append("如需查看某个组织的成员列表，请提供组织ID。");
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.error("[Tool] 查询组织异常", e);
            return "查询组织列表时发生错误，请稍后重试。";
        }
    }

    @Tool("列出指定组织的所有成员，包括用户名和角色")
    public String listOrgMembers(@P("组织ID，如 org_xxx 或 ent_xxx") String orgId) {
        log.info("[Tool] 查询组织成员 orgId={}", orgId);
        try {
            String userId = getCurrentUserId();
            if (userId == null) {
                return "无法识别当前用户身份，请先登录。";
            }
            if (orgId == null || orgId.isBlank()) {
                return "请提供组织ID（如 org_xxx 或 ent_xxx）。您可以先使用 listMyOrganizations 查看您加入的组织。";
            }

            if (!hasOrgAccess(orgId)) {
                return "权限不足：您不是该组织的成员，无法查看成员列表。";
            }

            List<Map<String, Object>> members = organizationService.getOrgMembersWithUsername(orgId);
            if (members.isEmpty()) {
                return String.format("组织 %s 暂无成员。", orgId);
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("组织 %s 共有 %d 名成员：\n\n", orgId, members.size()));
            for (int i = 0; i < members.size(); i++) {
                Map<String, Object> m = members.get(i);
                String username = (String) m.get("username");
                String role = (String) m.get("role");
                String joinedAt = m.get("joinedAt") != null ? m.get("joinedAt").toString() : "未知";

                String roleLabel = switch (role) {
                    case "OWNER" -> "拥有者";
                    case "ADMIN" -> "管理员";
                    case "MEMBER" -> "成员";
                    default -> role;
                };

                sb.append(String.format("%d. %s（%s）- 加入时间：%s\n",
                        i + 1, username, roleLabel, joinedAt));
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.error("[Tool] 查询组织成员异常 orgId={}", orgId, e);
            return String.format("查询组织 %s 成员时发生错误，请稍后重试。", orgId);
        }
    }

    // ── 2. 知识库查询 ──────────────────────────────────────────

    @Tool("列出用户可访问的知识库，可按组织名称过滤。返回知识库名称、文档数量、状态")
    public String listMyKnowledgeBases(
            @P("组织名称过滤（可选），如不填或留空则列出所有组织的知识库") String orgName) {
        log.info("[Tool] 查询我的知识库 orgName={}", orgName);
        try {
            String userId = getCurrentUserId();
            if (userId == null) {
                return "无法识别当前用户身份，请先登录。";
            }

            List<Map<String, Object>> orgs = organizationService.getUserOrganizationsWithDetail(userId);
            if (orgs.isEmpty()) {
                return "您当前尚未加入任何组织，暂无知识库。";
            }

            List<Map<String, Object>> targetOrgs = orgs;
            if (orgName != null && !orgName.isBlank()) {
                String filter = orgName.toLowerCase();
                targetOrgs = orgs.stream()
                        .filter(o -> {
                            String name = (String) o.get("name");
                            String oid = (String) o.get("orgId");
                            return (name != null && name.toLowerCase().contains(filter))
                                    || (oid != null && oid.toLowerCase().contains(filter));
                        })
                        .toList();
                if (targetOrgs.isEmpty()) {
                    return String.format("未找到名称包含「%s」的组织。您可以先使用 listMyOrganizations 查看可用的组织。",
                            orgName);
                }
            }

            StringBuilder sb = new StringBuilder();
            int totalKbs = 0;
            boolean first = true;

            for (Map<String, Object> org : targetOrgs) {
                String oid = (String) org.get("orgId");
                String name = (String) org.get("name");

                try {
                    List<KnowledgeBase> kbs = kbService.listKnowledgeBases(oid);
                    if (kbs.isEmpty()) continue;

                    if (!first) sb.append("\n");
                    first = false;

                    sb.append(String.format("【%s】共 %d 个知识库：\n", name, kbs.size()));
                    for (KnowledgeBase kb : kbs) {
                        String statusLabel = kb.getStatus() != null && kb.getStatus() == 1 ? "正常"
                                : (kb.getStatus() != null ? "已归档" : "未知");
                        sb.append(String.format("  · %s（ID: %d）- 文档数：%d，状态：%s\n",
                                kb.getName(), kb.getId(), kb.getDocCount(), statusLabel));
                    }
                    totalKbs += kbs.size();
                } catch (Exception e) {
                    log.warn("[Tool] 查询组织 {} 的知识库失败: {}", oid, e.getMessage());
                }
            }

            if (totalKbs == 0) {
                return "您当前没有可访问的知识库。您可以在组织中创建知识库，或让管理员为您授权。";
            }

            sb.append(String.format("\n共 %d 个知识库。", totalKbs));
            sb.append("\n如需查看某个知识库中的文档列表及解析状态，请提供知识库ID。");
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.error("[Tool] 查询知识库列表异常", e);
            return "查询知识库列表时发生错误，请稍后重试。";
        }
    }

    @Tool("列出指定知识库中的所有文档，按解析状态分组（解析成功/处理中/失败），并展示失败原因")
    public String listKbDocuments(@P("知识库ID，数字类型，如 1、2") Long kbId) {
        log.info("[Tool] 查询知识库文档 kbId={}", kbId);
        try {
            String userId = getCurrentUserId();
            if (userId == null) {
                return "无法识别当前用户身份，请先登录。";
            }
            if (kbId == null) {
                return "请提供知识库ID（数字）。您可以先使用 listMyKnowledgeBases 查看可用的知识库。";
            }

            Optional<KnowledgeBase> kbOpt = kbService.findById(kbId);
            if (kbOpt.isEmpty()) {
                return String.format("知识库 %d 不存在，请确认ID是否正确。", kbId);
            }
            KnowledgeBase kb = kbOpt.get();

            // 权限检查：先验证用户是否属于 KB 所在组织，再查 kb_member
            boolean isOrgMember = organizationService.isMemberOf(kb.getTenantId(), userId);
            String role = kbMemberService.checkAccess(kbId, userId,
                    isOrgMember ? kb.getTenantId() : null);
            if (role == null) {
                return String.format("权限不足：您没有访问知识库「%s」（ID: %d）的权限。",
                        kb.getName(), kbId);
            }

            List<Document> docs = kbService.getDocuments(kb.getTenantId(), kbId);
            if (docs.isEmpty()) {
                return String.format("知识库「%s」（ID: %d）中暂无文档。您可以在前端上传 PDF、Word、TXT 等格式的文档。",
                        kb.getName(), kbId);
            }

            List<Document> doneDocs = new ArrayList<>();
            List<Document> processingDocs = new ArrayList<>();
            List<Document> failedDocs = new ArrayList<>();

            for (Document doc : docs) {
                String status = doc.getParseStatus();
                if ("DONE".equals(status)) {
                    doneDocs.add(doc);
                } else if ("FAILED".equals(status)) {
                    failedDocs.add(doc);
                } else {
                    processingDocs.add(doc);
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("知识库「%s」（ID: %d）共有 %d 个文档：\n",
                    kb.getName(), kbId, docs.size()));

            sb.append(String.format("\n✅ 解析成功（%d 个）\n", doneDocs.size()));
            if (doneDocs.isEmpty()) {
                sb.append("  （无）\n");
            } else {
                for (Document doc : doneDocs) {
                    sb.append(String.format("  · %s（%s）- %d 个片段，索引时间：%s\n",
                            doc.getName(), doc.getDocType(),
                            doc.getChunkCount(),
                            doc.getIndexedAt() != null ? DATE_FMT.format(doc.getIndexedAt()) : "未知"));
                }
            }

            sb.append(String.format("\n⏳ 处理中（%d 个）\n", processingDocs.size()));
            if (processingDocs.isEmpty()) {
                sb.append("  （无）\n");
            } else {
                for (Document doc : processingDocs) {
                    String statusLabel = switch (doc.getParseStatus()) {
                        case "PENDING" -> "等待处理";
                        case "PARSING" -> "文档解析中";
                        case "CHUNKING" -> "文本切片中";
                        case "EMBEDDING" -> "向量化中";
                        default -> doc.getParseStatus();
                    };
                    sb.append(String.format("  · %s（%s）- 状态：%s\n",
                            doc.getName(), doc.getDocType(), statusLabel));
                }
            }

            sb.append(String.format("\n❌ 解析失败（%d 个）\n", failedDocs.size()));
            if (failedDocs.isEmpty()) {
                sb.append("  （无）\n");
            } else {
                for (Document doc : failedDocs) {
                    String error = doc.getParseError() != null && !doc.getParseError().isBlank()
                            ? doc.getParseError() : "未知错误";
                    if (error.length() > 200) error = error.substring(0, 200) + "...";
                    sb.append(String.format("  · %s（%s）- 失败原因：%s\n",
                            doc.getName(), doc.getDocType(), error));
                }
            }

            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.error("[Tool] 查询知识库文档异常 kbId={}", kbId, e);
            return String.format("查询知识库 %d 文档时发生错误，请稍后重试。", kbId);
        }
    }

    // ── 3. 系统能力与部署 ──────────────────────────────────────────

    @Tool("获取当前AI助手系统支持的所有功能与能力说明")
    public String getSystemCapabilities() {
        return """
                我是基于 Spring Boot + LangChain4j 构建的企业级 AI 助手，支持以下能力：

                ## 对话模式
                · 同步对话：POST /api/v1/chat，等待完整回复
                · 流式对话：GET /api/v1/chat/stream，逐字实时推送
                · ReAct 推理：复杂任务自动拆解、多步工具调用

                ## 知识库（RAG）
                · 上传 PDF/Word/TXT 等文档，上传即可问答
                · 混合检索引擎：HyDE 查询改写 → 向量检索 → BM25 检索 → RRF 融合 → Reranker 精排
                · 4 种 Reranker 可选：LLM / TF-IDF / BGE / Cohere
                · 答案自动标注来源文档和段落，支持置信度评估

                ## 组织管理
                · 多租户隔离：每个用户自动创建个人空间，支持创建企业组织
                · 成员管理：邀请/移除成员，OWNER/ADMIN/MEMBER 角色体系
                · 知识库可跨组织授权（kb_member 细粒度权限）

                ## 安全防护
                · JWT 无状态认证（Bearer Token）
                · Prompt 注入检测（覆盖中英文 7 种攻击模式）
                · 输出内容脱敏（手机号/身份证/银行卡号/密码）
                · 用户级限流（每分钟+每日双维度，Redis 令牌桶）
                · 操作审计（登录/对话/安全拦截事件异步记录）

                ## 可观测性
                · Token 成本追踪（每次 LLM 调用 Token 数和 USD 费用入库）
                · 成本报表 API（按用户/模型/天统计）
                · 7 类 Prometheus 指标（调用次数/P99延迟/错误率等）
                · 全链路 TraceId 贯穿，支持 Zipkin 上报
                · 智能告警（钉钉/企微/邮件/Webhook 多渠道通知）

                ## 可用工具
                · 组织查询：查看我加入的组织、查看组织成员
                · 知识库查询：查看我的知识库、查看知识库文档及解析状态
                · 部署指南：获取系统部署方案和快速开始指引

                如需了解部署方案，请说「如何部署」或「部署指南」。
                """;
    }

    @Tool("获取系统的部署方案、环境配置和快速开始指引")
    public String getDeploymentGuide() {
        return """
                ## 部署方案

                ### 前置要求
                - JDK 21+（必须）
                - Maven 3.8+
                - Node.js 20+（前端独立启动）
                - Docker 24+
                - DeepSeek API Key（platform.deepseek.com 注册申请）

                ### 第一步：启动依赖服务
                ```
                git clone <your-repo-url>
                cd ai-agent
                docker-compose up -d postgres redis
                docker-compose ps   # 确认 Status 为 healthy
                ```

                ### 第二步：配置环境变量
                ```
                export DEEPSEEK_API_KEY=sk-xxx          # 必填
                export SPRING_PROFILES_ACTIVE=deepseek  # 必填
                export JWT_SECRET=<至少32字符的随机字符串>  # 必填
                # 可选
                export PG_HOST=localhost
                export PG_PASSWORD=postgres
                export REDIS_HOST=localhost
                ```

                ### 第三步：启动后端
                ```
                java -version   # 确认显示 21.x.x
                mvn spring-boot:run  # Flyway 自动建表
                ```

                ### 第四步：启动前端
                ```
                cd frontend
                npm install
                npm run dev   # http://localhost:5173
                ```

                ### 生产部署
                ```
                export SPRING_PROFILES_ACTIVE=deepseek,prod
                java -Xmx2g -jar ai-agent-1.0.0.jar
                ```

                ### 可选：启用 Elasticsearch BM25
                ```
                docker-compose --profile bm25 up -d elasticsearch
                export ES_ENABLED=true
                ```

                ### 常见问题
                - WeakKeyException → JWT_SECRET 至少 32 字符
                - 编译报 Text Block / record 语法错误 → 需要 Java 21
                - 知识库上传后查询不到 → 确认 pgvector 扩展已安装
                - 切换对话模型 → 设置 SPRING_PROFILES_ACTIVE=deepseek 或 claude
                - Embedding 统一使用 DeepSeek，切换模型无需重建知识库

                ### 环境变量清单
                DEEPSEEK_API_KEY / ANTHROPIC_API_KEY（LLM）
                JWT_SECRET（安全）
                PG_HOST / PG_PORT / PG_DB / PG_USER / PG_PASSWORD（数据库）
                REDIS_HOST / REDIS_PASSWORD（Redis）
                ES_ENABLED / ES_HOST（可选 Elasticsearch）
                ALERT_DINGTALK_ENABLED / ALERT_DINGTALK_WEBHOOK 等（可选告警）
                """;
    }

    // ── 私有辅助方法 ──────────────────────────────────────────────

    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return String.valueOf(auth.getPrincipal());
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> "ROLE_ADMIN".equals(a) || "ADMIN".equals(a));
    }

    private boolean hasOrgAccess(String orgId) {
        if (isAdmin()) return true;
        String userId = getCurrentUserId();
        if (userId == null) return false;
        return organizationService.isMemberOf(orgId, userId);
    }

}
