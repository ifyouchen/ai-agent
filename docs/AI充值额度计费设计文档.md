# AI 充值、额度与计费设计文档

## 目标

本方案为 AI Agent 增加用户级预付费钱包、充值订单、额度校验、LLM 调用冻结与结算能力。v1 采用人民币余额预付费模式，支付渠道规划为支付宝和微信，现有 Token 用量统计继续保留为报表与审计数据源。

## 计费模式

- 账户范围：用户级钱包，组织仅做统计维度，v1 不做组织共享钱包。
- 充值币种：人民币 CNY。
- 套餐：固定金额充值，v1 不做赠送余额。
- 模型成本：模型原始成本按 USD / 1M tokens 配置，折算为 CNY 后扣费。
- 扣费公式：`actualCny = costUsd * usdCnyRate * markupRate`。
- 默认参数：`usdCnyRate = 7.30`，`markupRate = 1.20`。

## 套餐

| 套餐编码 | 金额 |
| --- | ---: |
| CNY_10 | ¥10 |
| CNY_30 | ¥30 |
| CNY_100 | ¥100 |
| CNY_300 | ¥300 |

金额计算约束：

- 支付订单金额使用整数分。
- 钱包余额、冻结金额、AI 扣费使用 `DECIMAL(18,6)`。
- Java 侧金额计算使用 `BigDecimal`，禁止使用 `double` 作为账务结果。

## 钱包与调用链路

LLM 调用采用短事务三段式：

1. 调用前冻结：按模型、输入 token、最大输出 token 预估费用，原子冻结余额。
2. 模型调用：不持有数据库事务。
3. 调用后结算：按实际 token 成本扣费，退回冻结差额，写入钱包流水。

关键原子 SQL：

```sql
UPDATE billing_wallet
SET available_balance_cny = available_balance_cny - ?,
    frozen_balance_cny = frozen_balance_cny + ?,
    updated_at = NOW()
WHERE user_id = ?
  AND status = 'ACTIVE'
  AND available_balance_cny >= ?;
```

如果受影响行数为 0，表示余额不足或账户不可用，应阻止 LLM 调用。

## 支付链路

充值订单由服务端根据套餐生成金额，前端只允许传 `packageCode` 和 `payChannel`，不能传任意金额。

支付成功以服务端异步通知和主动查单为准：

- 支付宝：验签后校验 `out_trade_no`、`total_amount`、`trade_status`、`app_id/seller_id`。
- 微信：校验 API v3 回调签名，解密通知内容后校验 `out_trade_no`、`transaction_id`、`amount.total`、`appid/mchid`、`trade_state`。
- 同一订单或同一三方交易号重复通知只入账一次。
- 前端成功页只轮询订单状态，不负责入账。

当前实现先落安全骨架，真实支付宝/微信 SDK 适配作为后续支付网关实现项；在未配置支付网关时，系统可创建订单但不会伪造支付成功。

## 安全与风控

- 回调接口放行 JWT，但必须依赖三方支付签名与订单金额校验。
- 订单过期时间默认 15 分钟。
- 支付金额、渠道、订单号必须与本地订单一致。
- 钱包流水使用唯一幂等键，防止重复入账、重复扣费、重复释放冻结。
- 余额不足、单次预估费用超限、日消费超限均应阻止调用。
- 支付密钥、商户号、应用号只能通过配置或环境变量注入，不进入前端。

## 性能说明

同步事务化扣费只包含调用前冻结和调用后结算两个短事务，不在数据库事务中调用模型。每次 LLM 请求新增两次短数据库写操作，主要锁粒度为单个用户的钱包行，不影响不同用户并发。

