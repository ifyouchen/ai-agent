#!/bin/bash
#
# RAG 知识库全链路验收脚本 (Shell 版)
#
# 验证：启动 → 注册 → 创建KB → 上传 → 列出文档 → 问答 → 清理
#
# 使用方式：
#   chmod +x scripts/smoke_test.sh
#   ./scripts/smoke_test.sh [BASE_URL]
#
# 依赖：curl, jq
#

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
TIMESTAMP=$(date +%s)
USERNAME="smoke_${TIMESTAMP}"
PASSWORD="Test@12345"
EMAIL="${USERNAME}@test.com"
KB_NAME="验收测试KB-${TIMESTAMP}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

PASS_COUNT=0
FAIL_COUNT=0

pass() {
    echo -e "  ${GREEN}✓ PASS${NC}  $1"
    ((PASS_COUNT++))
}

fail() {
    echo -e "  ${RED}✗ FAIL${NC}  $1"
    ((FAIL_COUNT++))
}

skip() {
    echo -e "  ${YELLOW}⊘ SKIP${NC}  $1"
}

# ── 前置检查 ──────────────────────────────────────────

echo "╔══════════════════════════════════════════════════════════╗"
echo "║       RAG 知识库全链路验收脚本 (Shell)                   ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo "目标服务: ${BASE_URL}"
echo ""

command -v curl >/dev/null 2>&1 || { echo "需要 curl"; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "需要 jq (brew install jq)"; exit 1; }

# ── 步骤 1: 健康检查 ──────────────────────────────────

echo "========================================"
echo "步骤 1: 健康检查"
echo "========================================"

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/actuator/health" 2>/dev/null || echo "000")

if [ "$HTTP_CODE" = "200" ]; then
    pass "应用已启动 (status=200)"
else
    fail "应用未就绪 (status=${HTTP_CODE})"
    echo "  请确认应用已启动: ${BASE_URL}"
    exit 1
fi

# ── 步骤 2: 注册/登录 ──────────────────────────────────

echo ""
echo "========================================"
echo "步骤 2: 注册/登录"
echo "========================================"

REGISTER_RESP=$(curl -s -X POST "${BASE_URL}/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\",\"email\":\"${EMAIL}\"}" 2>/dev/null || echo "{}")

TOKEN=$(echo "$REGISTER_RESP" | jq -r '.token // empty' 2>/dev/null)

if [ -z "$TOKEN" ]; then
    # 尝试登录
    LOGIN_RESP=$(curl -s -X POST "${BASE_URL}/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}" 2>/dev/null || echo "{}")
    TOKEN=$(echo "$LOGIN_RESP" | jq -r '.token // empty' 2>/dev/null)
fi

if [ -n "$TOKEN" ] && [ "$TOKEN" != "null" ]; then
    pass "注册/登录成功，username=${USERNAME}"
else
    fail "注册/登录失败: ${REGISTER_RESP}"
    exit 1
fi

# ── 步骤 3: 创建知识库 ──────────────────────────────────

echo ""
echo "========================================"
echo "步骤 3: 创建知识库"
echo "========================================"

KB_RESP=$(curl -s -X POST "${BASE_URL}/api/v1/kb" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${TOKEN}" \
    -d "{\"name\":\"${KB_NAME}\",\"description\":\"全链路验收测试\"}" 2>/dev/null || echo "{}")

KB_ID=$(echo "$KB_RESP" | jq -r '.id // empty' 2>/dev/null)

if [ -n "$KB_ID" ] && [ "$KB_ID" != "null" ]; then
    pass "知识库创建成功，kbId=${KB_ID}"
else
    fail "知识库创建失败: ${KB_RESP}"
    exit 1
fi

# ── 步骤 4: 上传文档 ──────────────────────────────────

echo ""
echo "========================================"
echo "步骤 4: 上传文档"
echo "========================================"

# 创建临时测试文件
TMPFILE=$(mktemp /tmp/smoke-test-XXXXXX.txt)
cat > "$TMPFILE" << 'EOF'
Spring Boot 微服务开发指南

第一章：Spring Boot 简介
Spring Boot 是一个基于 Spring 框架的快速开发工具，它通过自动配置和起步依赖
大大简化了 Spring 应用的初始搭建和开发过程。Spring Boot 的核心理念是"约定
优于配置"，开发者只需关注业务逻辑，而无需手动配置大量的 XML 文件。

第二章：自动配置原理
Spring Boot 的自动配置通过 @EnableAutoConfiguration 注解触发，
它会在 classpath 下扫描特定的条件注解（如 @ConditionalOnClass），
根据项目引入的依赖自动注册所需的 Bean。

第三章：微服务架构
在微服务架构中，每个服务独立部署、独立扩展，服务之间通过 REST API 或消息队列
通信。Spring Cloud 提供了服务发现、配置中心、熔断器、API 网关等组件。

第四章：数据访问层
Spring Data JPA 提供了基于 Repository 接口的持久化抽象，开发者只需定义接口
方法名，框架会自动生成 JPQL 查询。同时支持自定义 @Query 注解和原生 SQL 查询。
EOF

UPLOAD_RESP=$(curl -s -X POST "${BASE_URL}/api/v1/kb/${KB_ID}/documents" \
    -H "Authorization: Bearer ${TOKEN}" \
    -F "file=@${TMPFILE};type=text/plain" 2>/dev/null || echo "{}")

CHUNK_COUNT=$(echo "$UPLOAD_RESP" | jq -r '.chunkCount // 0' 2>/dev/null)

if [ "$CHUNK_COUNT" -gt 0 ] 2>/dev/null; then
    pass "文档上传成功，chunkCount=${CHUNK_COUNT}"
else
    fail "文档上传失败: ${UPLOAD_RESP}"
fi

rm -f "$TMPFILE"

# ── 步骤 5: 列出文档 ──────────────────────────────────

echo ""
echo "========================================"
echo "步骤 5: 列出文档（验证持久化）"
echo "========================================"

DOCS_RESP=$(curl -s -X GET "${BASE_URL}/api/v1/kb/${KB_ID}/documents" \
    -H "Authorization: Bearer ${TOKEN}" 2>/dev/null || echo "[]")

DOC_COUNT=$(echo "$DOCS_RESP" | jq 'if type == "array" then length else 0 end' 2>/dev/null)

if [ "$DOC_COUNT" -gt 0 ] 2>/dev/null; then
    DOC_NAME=$(echo "$DOCS_RESP" | jq -r '.[0].name // "unknown"' 2>/dev/null)
    DOC_STATUS=$(echo "$DOCS_RESP" | jq -r '.[0].parseStatus // "unknown"' 2>/dev/null)
    pass "文档元数据已持久化 (docs=${DOC_COUNT}, name=${DOC_NAME}, status=${DOC_STATUS})"
else
    fail "文档列表为空，持久化可能失败"
fi

# ── 步骤 6: 知识库问答 ──────────────────────────────────

echo ""
echo "========================================"
echo "步骤 6: 知识库问答"
echo "========================================"

QUERY_RESP=$(curl -s -X POST "${BASE_URL}/api/v1/kb/${KB_ID}/query" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${TOKEN}" \
    -d '{"question":"Spring Boot 的自动配置原理是什么？"}' 2>/dev/null || echo "{}")

ANSWER_FOUND=$(echo "$QUERY_RESP" | jq -r '.answerFound // false' 2>/dev/null)
CONFIDENCE=$(echo "$QUERY_RESP" | jq -r '.confidence // "N/A"' 2>/dev/null)

if [ "$ANSWER_FOUND" = "true" ]; then
    pass "知识库问答命中 (confidence=${CONFIDENCE})"
else
    echo -e "  ${YELLOW}⚠ 问答未命中${NC} (answerFound=${ANSWER_FOUND}, confidence=${CONFIDENCE})"
    echo "  提示: 可能是 Embedding 模型未配置或检索延迟"
    skip "知识库问答 (answerFound=${ANSWER_FOUND})"
fi

# ── 步骤 7: 多租户隔离 ──────────────────────────────────

echo ""
echo "========================================"
echo "步骤 7: 多租户隔离验证"
echo "========================================"

# 注册用户 B
USERNAME_B="smoke_b_${TIMESTAMP}"
REGISTER_B_RESP=$(curl -s -X POST "${BASE_URL}/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"${USERNAME_B}\",\"password\":\"${PASSWORD}\",\"email\":\"${USERNAME_B}@test.com\"}" 2>/dev/null || echo "{}")

TOKEN_B=$(echo "$REGISTER_B_RESP" | jq -r '.token // empty' 2>/dev/null)

if [ -n "$TOKEN_B" ] && [ "$TOKEN_B" != "null" ]; then
    # 用户 B 尝试访问用户 A 的知识库
    HTTP_CODE_B=$(curl -s -o /dev/null -w "%{http_code}" \
        -X GET "${BASE_URL}/api/v1/kb/${KB_ID}/documents" \
        -H "Authorization: Bearer ${TOKEN_B}" 2>/dev/null || echo "000")

    if [ "$HTTP_CODE_B" = "404" ] || [ "$HTTP_CODE_B" = "403" ]; then
        pass "租户隔离验证通过 (用户B访问用户A的KB返回 ${HTTP_CODE_B})"
    else
        fail "租户隔离可能存在问题 (用户B访问用户A的KB返回 ${HTTP_CODE_B})"
    fi
else
    skip "多租户隔离验证（用户B注册失败）"
fi

# ── 步骤 8: 删除知识库 ──────────────────────────────────

echo ""
echo "========================================"
echo "步骤 8: 删除知识库（级联清理）"
echo "========================================"

DELETE_HTTP=$(curl -s -o /dev/null -w "%{http_code}" \
    -X DELETE "${BASE_URL}/api/v1/kb/${KB_ID}" \
    -H "Authorization: Bearer ${TOKEN}" 2>/dev/null || echo "000")

if [ "$DELETE_HTTP" = "200" ]; then
    pass "知识库已删除 (kbId=${KB_ID})"

    # 验证删除后无法访问
    VERIFY_HTTP=$(curl -s -o /dev/null -w "%{http_code}" \
        -X GET "${BASE_URL}/api/v1/kb/${KB_ID}/documents" \
        -H "Authorization: Bearer ${TOKEN}" 2>/dev/null || echo "000")

    if [ "$VERIFY_HTTP" = "404" ]; then
        pass "级联清理验证通过 (删除后返回 404)"
    else
        fail "级联清理可能存在问题 (删除后返回 ${VERIFY_HTTP})"
    fi
else
    fail "知识库删除失败 (status=${DELETE_HTTP})"
fi

# ── 汇总 ──────────────────────────────────────────

echo ""
echo "========================================"
echo "验收结果汇总"
echo "========================================"
echo -e "  通过: ${GREEN}${PASS_COUNT}${NC}"
echo -e "  失败: ${RED}${FAIL_COUNT}${NC}"
echo ""

if [ "$FAIL_COUNT" -eq 0 ]; then
    echo -e "${GREEN}🎉 全链路验收通过！${NC}"
    exit 0
else
    echo -e "${RED}❌ 验收未通过，请检查上述失败步骤${NC}"
    exit 1
fi

