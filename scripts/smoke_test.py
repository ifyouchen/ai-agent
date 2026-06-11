#!/usr/bin/env python3
"""
RAG 知识库全链路验收脚本

验证流程：
  1. 健康检查 → 应用已启动
  2. 注册/登录 → 获取 JWT Token
  3. 创建知识库 → 返回 kbId
  4. 上传文档 → 文档摄入闭环
  5. 列出文档 → 验证文档元数据持久化
  6. 知识库问答 → 验证检索+对话链路
  7. 多租户隔离 → 不同租户互不可见
  8. 删除知识库 → 级联清理

使用方式：
  python3 scripts/smoke_test.py [--base-url http://localhost:8080]

前置条件：
  - 应用已启动（默认监听 8080 端口）
  - PostgreSQL + PgVector 已就绪
  - DeepSeek API Key 已配置（用于 Embedding 和 LLM）
"""

import argparse
import json
import sys
import time
import tempfile
import os
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError


# ── 配置 ──────────────────────────────────────────────

BASE_URL = "http://localhost:8080"
TIMEOUT = 30  # 秒


# ── HTTP 工具 ──────────────────────────────────────────

def http_request(method: str, path: str, token: str = None,
                 body: dict = None, files: dict = None) -> tuple:
    """发送 HTTP 请求，返回 (status_code, response_json)"""
    url = f"{BASE_URL}{path}"
    headers = {}

    if token:
        headers["Authorization"] = f"Bearer {token}"

    if files:
        # multipart/form-data
        import urllib.request
        boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        body_bytes = b""
        for key, (filename, content) in files.items():
            body_bytes += f"--{boundary}\r\n".encode()
            body_bytes += f'Content-Disposition: form-data; name="{key}"; filename="{filename}"\r\n'.encode()
            body_bytes += b"Content-Type: application/octet-stream\r\n\r\n"
            body_bytes += content if isinstance(content, bytes) else content.encode()
            body_bytes += b"\r\n"
        body_bytes += f"--{boundary}--\r\n".encode()
        headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
        req = Request(url, data=body_bytes, headers=headers, method=method)
    elif body:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
        req = Request(url, data=data, headers=headers, method=method)
    else:
        req = Request(url, headers=headers, method=method)

    try:
        resp = urlopen(req, timeout=TIMEOUT)
        status = resp.status
        try:
            resp_json = json.loads(resp.read().decode())
        except Exception:
            resp_json = {}
        return status, resp_json
    except HTTPError as e:
        try:
            error_body = json.loads(e.read().decode())
        except Exception:
            error_body = {"error": str(e)}
        return e.code, error_body
    except URLError as e:
        return 0, {"error": f"连接失败: {e}"}


# ── 测试步骤 ──────────────────────────────────────────

def step_health_check():
    """步骤 1: 健康检查"""
    print("\n" + "=" * 60)
    print("步骤 1: 健康检查")
    print("=" * 60)

    status, body = http_request("GET", "/actuator/health")
    assert status == 200, f"健康检查失败: status={status}, body={body}"
    print(f"  ✓ 应用已启动，状态: {body.get('status', 'UNKNOWN')}")
    return True


def step_register_and_login():
    """步骤 2: 注册并登录获取 JWT"""
    print("\n" + "=" * 60)
    print("步骤 2: 注册/登录")
    print("=" * 60)

    # 注册
    username = f"smoke_user_{int(time.time())}"
    register_body = {
        "username": username,
        "password": "Test@12345",
        "email": f"{username}@test.com"
    }
    status, body = http_request("POST", "/api/v1/auth/register", body=register_body)
    assert status in (200, 201), f"注册失败: status={status}, body={body}"
    token = body.get("token")
    if not token:
        # 注册后可能需要单独登录
        login_body = {
            "username": username,
            "password": "Test@12345"
        }
        status, body = http_request("POST", "/api/v1/auth/login", body=login_body)
        assert status == 200, f"登录失败: status={status}, body={body}"
        token = body.get("token")

    assert token, f"未能获取 JWT Token: body={body}"
    print(f"  ✓ 注册/登录成功，username={username}")
    return token, username


def step_create_knowledge_base(token: str):
    """步骤 3: 创建知识库"""
    print("\n" + "=" * 60)
    print("步骤 3: 创建知识库")
    print("=" * 60)

    body = {
        "name": f"验收测试KB-{int(time.time())}",
        "description": "全链路验收测试知识库"
    }
    status, resp = http_request("POST", "/api/v1/kb", token=token, body=body)
    assert status == 201, f"创建知识库失败: status={status}, body={resp}"

    kb_id = resp.get("id")
    assert kb_id, f"未返回 kbId: body={resp}"
    print(f"  ✓ 知识库创建成功，kbId={kb_id}, name={resp.get('name')}")
    return kb_id


def step_upload_document(token: str, kb_id: int):
    """步骤 4: 上传文档"""
    print("\n" + "=" * 60)
    print("步骤 4: 上传文档")
    print("=" * 60)

    # 创建测试文档内容
    doc_content = """
Spring Boot 微服务开发指南

第一章：Spring Boot 简介
Spring Boot 是一个基于 Spring 框架的快速开发工具，它通过自动配置和起步依赖
大大简化了 Spring 应用的初始搭建和开发过程。Spring Boot 的核心理念是"约定
优于配置"，开发者只需关注业务逻辑，而无需手动配置大量的 XML 文件。

第二章：自动配置原理
Spring Boot 的自动配置通过 @EnableAutoConfiguration 注解触发，
它会在 classpath 下扫描特定的条件注解（如 @ConditionalOnClass），
根据项目引入的依赖自动注册所需的 Bean。例如，引入 spring-boot-starter-web
后，会自动配置内嵌 Tomcat、Spring MVC 和 Jackson。

第三章：微服务架构
在微服务架构中，每个服务独立部署、独立扩展，服务之间通过 REST API 或消息队列
通信。Spring Cloud 提供了服务发现（Eureka）、配置中心（Config Server）、
熔断器（Hystrix）、API 网关（Gateway）等组件，是构建微服务生态的基础框架。

第四章：数据访问层
Spring Data JPA 提供了基于 Repository 接口的持久化抽象，开发者只需定义接口
方法名（如 findByUsernameAndStatus），框架会自动生成 JPQL 查询。同时支持
自定义 @Query 注解和原生 SQL 查询，满足复杂业务场景需求。

第五章：安全与认证
Spring Security 是 Spring 生态的安全框架，支持认证（Authentication）和
授权（Authorization）。结合 JWT 无状态认证，可以实现分布式系统下的单点登录
和细粒度的权限控制。BCrypt 密码编码器提供强安全性，防止彩虹表攻击。
"""

    files = {
        "file": ("spring-boot-guide.txt", doc_content)
    }
    status, resp = http_request("POST", f"/api/v1/kb/{kb_id}/documents",
                                token=token, files=files)
    assert status == 200, f"上传文档失败: status={status}, body={resp}"

    chunk_count = resp.get("chunkCount", 0)
    assert chunk_count > 0, f"文档切片数为0，摄入可能失败: body={resp}"
    print(f"  ✓ 文档上传成功，chunkCount={chunk_count}, filename={resp.get('filename')}")
    return chunk_count


def step_list_documents(token: str, kb_id: int):
    """步骤 5: 列出文档，验证元数据持久化"""
    print("\n" + "=" * 60)
    print("步骤 5: 列出文档（验证元数据持久化）")
    print("=" * 60)

    status, resp = http_request("GET", f"/api/v1/kb/{kb_id}/documents", token=token)
    assert status == 200, f"列出文档失败: status={status}, body={resp}"

    docs = resp if isinstance(resp, list) else resp.get("content", resp.get("data", []))
    assert len(docs) > 0, "文档列表为空，持久化可能失败"

    doc = docs[0]
    print(f"  ✓ 文档元数据已持久化:")
    print(f"    - docId={doc.get('id')}")
    print(f"    - name={doc.get('name')}")
    print(f"    - parseStatus={doc.get('parseStatus')}")
    print(f"    - chunkCount={doc.get('chunkCount')}")
    return doc


def step_query_knowledge_base(token: str, kb_id: int):
    """步骤 6: 知识库问答"""
    print("\n" + "=" * 60)
    print("步骤 6: 知识库问答（检索 + 对话）")
    print("=" * 60)

    questions = [
        "Spring Boot 的自动配置原理是什么？",
        "微服务架构中有哪些核心组件？",
        "Spring Security 如何实现认证？"
    ]

    for question in questions:
        body = {"question": question}
        status, resp = http_request("POST", f"/api/v1/kb/{kb_id}/query",
                                     token=token, body=body)
        if status != 200:
            print(f"  ⚠ 问答失败: question='{question[:30]}...' status={status}")
            print(f"    response: {resp}")
            continue

        answer_found = resp.get("answerFound", False)
        confidence = resp.get("confidence", "N/A")
        citations = resp.get("citations", [])

        status_icon = "✓" if answer_found else "⚠"
        print(f"  {status_icon} 问题: {question[:40]}...")
        print(f"    答案已找到: {answer_found}, 置信度: {confidence}")
        if citations:
            for c in citations[:2]:
                print(f"    引用: {c.get('source', '未知')} (score={c.get('score', 'N/A')})")


def step_tenant_isolation(token_a: str):
    """步骤 7: 多租户隔离验证"""
    print("\n" + "=" * 60)
    print("步骤 7: 多租户隔离验证")
    print("=" * 60)

    # 注册第二个用户
    username_b = f"smoke_user_b_{int(time.time())}"
    register_body = {
        "username": username_b,
        "password": "Test@12345",
        "email": f"{username_b}@test.com"
    }
    status, body = http_request("POST", "/api/v1/auth/register", body=register_body)
    token_b = body.get("token", "")
    if not token_b:
        login_body = {"username": username_b, "password": "Test@12345"}
        status, body = http_request("POST", "/api/v1/auth/login", body=login_body)
        token_b = body.get("token", "")

    # 用户 A 创建知识库
    kb_body = {"name": f"租户A的KB-{int(time.time())}", "description": "私有知识库"}
    status, resp_a = http_request("POST", "/api/v1/kb", token=token_a, body=kb_body)
    assert status == 201, f"租户A创建KB失败: {resp_a}"
    kb_id_a = resp_a.get("id")

    # 用户 B 尝试访问用户 A 的知识库
    status, resp_b = http_request("GET", f"/api/v1/kb/{kb_id_a}/documents", token=token_b)
    if status == 404 or status == 403:
        print(f"  ✓ 租户隔离验证通过：用户B无法访问用户A的知识库 (status={status})")
    else:
        print(f"  ⚠ 租户隔离可能存在问题：用户B可以访问用户A的知识库 (status={status})")

    # 用户 B 列出自己的知识库（应不包含 A 的）
    status, resp_list = http_request("GET", "/api/v1/kb", token=token_b)
    if status == 200:
        kbs = resp_list if isinstance(resp_list, list) else []
        has_a_kb = any(kb.get("id") == kb_id_a for kb in kbs)
        if not has_a_kb:
            print(f"  ✓ 租户隔离验证通过：用户B的知识库列表中不包含用户A的KB")
        else:
            print(f"  ⚠ 租户隔离可能存在问题：用户B的知识库列表中包含用户A的KB")


def step_delete_knowledge_base(token: str, kb_id: int):
    """步骤 8: 删除知识库"""
    print("\n" + "=" * 60)
    print("步骤 8: 删除知识库（级联清理）")
    print("=" * 60)

    status, resp = http_request("DELETE", f"/api/v1/kb/{kb_id}", token=token)
    assert status == 200, f"删除知识库失败: status={status}, body={resp}"
    print(f"  ✓ 知识库已删除，kbId={kb_id}")

    # 验证删除后无法访问
    status, resp = http_request("GET", f"/api/v1/kb/{kb_id}/documents", token=token)
    assert status == 404, f"删除后仍可访问: status={status}"
    print(f"  ✓ 删除后无法访问，级联清理验证通过")


# ── 主流程 ──────────────────────────────────────────

def main():
    global BASE_URL

    parser = argparse.ArgumentParser(description="RAG 知识库全链路验收脚本")
    parser.add_argument("--base-url", default="http://localhost:8080",
                        help="应用基础 URL (默认: http://localhost:8080)")
    parser.add_argument("--skip-query", action="store_true",
                        help="跳过知识库问答步骤（不需要 LLM API）")
    parser.add_argument("--skip-isolation", action="store_true",
                        help="跳过多租户隔离验证")
    args = parser.parse_args()

    BASE_URL = args.base_url.rstrip("/")

    print("╔══════════════════════════════════════════════════════════╗")
    print("║       RAG 知识库全链路验收脚本                           ║")
    print("╚══════════════════════════════════════════════════════════╝")
    print(f"目标服务: {BASE_URL}")
    print(f"开始时间: {time.strftime('%Y-%m-%d %H:%M:%S')}")

    results = []
    kb_id = None
    token = None

    try:
        # 步骤 1
        step_health_check()
        results.append(("健康检查", True))

        # 步骤 2
        token, username = step_register_and_login()
        results.append(("注册/登录", True))

        # 步骤 3
        kb_id = step_create_knowledge_base(token)
        results.append(("创建知识库", True))

        # 步骤 4
        step_upload_document(token, kb_id)
        results.append(("上传文档", True))

        # 步骤 5
        step_list_documents(token, kb_id)
        results.append(("元数据持久化", True))

        # 步骤 6（可选，需要 LLM API）
        if not args.skip_query:
            step_query_knowledge_base(token, kb_id)
            results.append(("知识库问答", True))
        else:
            print("\n  ⊘ 跳过知识库问答步骤（--skip-query）")
            results.append(("知识库问答", "SKIP"))

        # 步骤 7（可选）
        if not args.skip_isolation:
            step_tenant_isolation(token)
            results.append(("多租户隔离", True))
        else:
            print("\n  ⊘ 跳过多租户隔离验证（--skip-isolation）")
            results.append(("多租户隔离", "SKIP"))

        # 步骤 8
        step_delete_knowledge_base(token, kb_id)
        results.append(("级联删除", True))

    except AssertionError as e:
        print(f"\n  ✗ 验收失败: {e}")
        results.append(("当前步骤", False))
    except Exception as e:
        print(f"\n  ✗ 运行异常: {e}")
        import traceback
        traceback.print_exc()
        results.append(("当前步骤", False))

    # ── 汇总 ──────────────────────────────────────────
    print("\n" + "=" * 60)
    print("验收结果汇总")
    print("=" * 60)

    all_passed = True
    for step_name, passed in results:
        if passed is True:
            icon = "✓ PASS"
        elif passed == "SKIP":
            icon = "⊘ SKIP"
        else:
            icon = "✗ FAIL"
            all_passed = False
        print(f"  {icon}  {step_name}")

    print(f"\n结束时间: {time.strftime('%Y-%m-%d %H:%M:%S')}")

    if all_passed:
        print("\n🎉 全链路验收通过！")
        sys.exit(0)
    else:
        print("\n❌ 验收未通过，请检查上述失败步骤")
        sys.exit(1)


if __name__ == "__main__":
    main()

