# 小说 V1 样例说明

本目录保存 `小说skill/` 删除后的等价验收样例文本，用于持续验证 V1 的导入识别规则。

- `adaptation-equivalent.txt`：等价于 `改编-上岸 (1).docx` 的“小说改编方案/题材迁移”样例。
- `short-drama-equivalent.txt`：等价于 `剧本 (1).docx` 的“短剧分场稿初稿”样例。

这些文件不是原始 DOCX，只能证明识别规则和导入预览链路可用。真实样例文件回补后，仍需要使用 `scripts/story_v1_docx_acceptance.ps1` 的 `-AdaptationDocx` 与 `-ScriptDocx` 参数补跑验收。
