# Promotion Engine API 测试指南

## 环境准备

1. 确保 MySQL 和 Redis 已启动
2. 执行 `sql/init.sql` 初始化数据库和示例数据
3. 启动应用：`mvn spring-boot:run`
4. 打开 Knife4j 文档：**http://localhost:8080/doc.html**

## 示例数据概览

| 活动ID | 名称 | 类型 | 状态 | 优先级 | 门店 | 有效期 |
|--------|------|------|------|--------|------|--------|
| 1001 | 金卡会员专属价 | MEMBER_PRICE | ACTIVE | 10 | 101 | 2026全年 |
| 1002 | 全场8.8折优惠 | DISCOUNT | ACTIVE | 20 | 101 | 2026-04 |
| 1003 | 满200减30 | FULL_REDUCTION | ACTIVE | 30 | 101 | 2026-04 |
| 1004 | 满150送保温杯 | GIFT | ACTIVE | 5 | 101 | 2026-04 |
| 1005 | 大促满300减80 | FULL_REDUCTION | ACTIVE | 40 | 101 | 2026-04-10~20 |

> 冲突关系：活动 1003 与 1005 **互斥**（两个满减不叠加，优先级高的 1005 优先）

---

## 一、活动管理接口测试

### 1.1 创建满减活动

```
POST /api/v1/activity
```

```json
{
  "storeId": 101,
  "name": "五一满100减15",
  "type": "FULL_REDUCTION",
  "priority": 25,
  "startTime": "2026-05-01 00:00:00",
  "endTime": "2026-05-07 23:59:59",
  "ruleJson": "{\"tiers\": [{\"threshold\": 100, \"reduction\": 15}, {\"threshold\": 300, \"reduction\": 50}]}"
}
```

**预期**：返回 code=200，data 中包含新活动信息，status 为 `DRAFT`。

### 1.2 创建折扣活动

```
POST /api/v1/activity
```

```json
{
  "storeId": 102,
  "name": "新店开业7折",
  "type": "DISCOUNT",
  "priority": 50,
  "startTime": "2026-05-01 00:00:00",
  "endTime": "2026-05-31 23:59:59",
  "ruleJson": "{\"discountRate\": 0.70, \"minOrderAmount\": 0}"
}
```

### 1.3 创建赠品活动

```
POST /api/v1/activity
```

```json
{
  "storeId": 101,
  "name": "满300送马克杯",
  "type": "GIFT",
  "priority": 5,
  "startTime": "2026-05-01 00:00:00",
  "endTime": "2026-05-31 23:59:59",
  "ruleJson": "{\"minOrderAmount\": 300, \"gifts\": [{\"giftSkuId\": \"SKU_MUG_001\", \"giftSkuName\": \"定制马克杯\", \"giftQuantity\": 1, \"marketPrice\": 39.90}]}"
}
```

### 1.4 创建会员价活动

```
POST /api/v1/activity
```

```json
{
  "storeId": 101,
  "name": "钻石会员专属8折",
  "type": "MEMBER_PRICE",
  "priority": 15,
  "startTime": "2026-05-01 00:00:00",
  "endTime": "2026-12-31 23:59:59",
  "ruleJson": "{\"memberLevels\": [\"DIAMOND\"], \"discountRate\": 0.80}"
}
```

### 1.5 分页查询活动列表

```
GET /api/v1/activity?page=1&size=10
```

**预期**：返回分页结果，包含已有活动。

按状态过滤：

```
GET /api/v1/activity?page=1&size=10&status=ACTIVE
```

按类型过滤：

```
GET /api/v1/activity?page=1&size=10&type=FULL_REDUCTION
```

### 1.6 查询活动详情

```
GET /api/v1/activity/1001
```

**预期**：返回活动 1001（金卡会员专属价）的完整信息。

### 1.7 更新活动

> 使用 1.1 创建的活动 ID（从创建响应中获取）

```
PUT /api/v1/activity/{id}
```

```json
{
  "storeId": 101,
  "name": "五一满100减20（加码版）",
  "type": "FULL_REDUCTION",
  "priority": 25,
  "startTime": "2026-05-01 00:00:00",
  "endTime": "2026-05-07 23:59:59",
  "ruleJson": "{\"tiers\": [{\"threshold\": 100, \"reduction\": 20}, {\"threshold\": 300, \"reduction\": 60}]}"
}
```

**预期**：返回 code=200。再通过 GET 查询确认名称和规则已更新。

### 1.8 上线活动

```
PUT /api/v1/activity/{id}/publish
```

**预期**：返回 code=200。再查询该活动，status 变为 `ACTIVE`。

### 1.9 设置灰度

```
PUT /api/v1/activity/{id}/gray
```

```json
{
  "grayConfig": "{\"storeIds\": [101, 103], \"trafficPercent\": 30}"
}
```

**预期**：返回 code=200。再查询该活动，status 变为 `GRAY`，grayConfig 已更新。

### 1.10 下线活动

```
PUT /api/v1/activity/{id}/offline
```

**预期**：返回 code=200。再查询该活动，status 变为 `EXPIRED`。

### 1.11 删除活动

```
DELETE /api/v1/activity/{id}
```

**预期**：返回 code=200。再查询该活动返回 null（逻辑删除）。

---

## 二、促销计算接口测试

### 2.1 金卡会员 - 命中全部四类活动

```
POST /api/v1/promotion/calculate
```

```json
{
  "orderId": "ORD-TEST-001",
  "storeId": 101,
  "memberId": 888,
  "memberLevel": "GOLD",
  "totalAmount": 280.00,
  "items": [
    {"skuId": "SKU001", "skuName": "商品A", "quantity": 2, "unitPrice": 100.00, "subtotal": 200.00},
    {"skuId": "SKU002", "skuName": "商品B", "quantity": 1, "unitPrice": 80.00, "subtotal": 80.00}
  ]
}
```

**预期**（计算顺序：会员价 → 折扣 → 满减 → 赠品）：
- 会员价：280 × 0.90 = 252.00（优惠 28.00）
- 折扣：252 × 0.88 = 221.76（优惠 30.24）
- 满减：221.76 ≥ 200 → 减 30（优惠 30.00），最终 191.76
- 赠品：280 ≥ 150 → 送定制保温杯 ×1
- `totalDiscount` ≈ 88.24，`finalAmount` ≈ 191.76
- `gifts` 包含保温杯

### 2.2 非会员 - 不命中会员价

```json
{
  "orderId": "ORD-TEST-002",
  "storeId": 101,
  "memberId": null,
  "memberLevel": null,
  "totalAmount": 280.00,
  "items": [
    {"skuId": "SKU001", "skuName": "商品A", "quantity": 2, "unitPrice": 100.00, "subtotal": 200.00},
    {"skuId": "SKU002", "skuName": "商品B", "quantity": 1, "unitPrice": 80.00, "subtotal": 80.00}
  ]
}
```

**预期**：
- 跳过会员价
- 折扣：280 × 0.88 = 246.40（优惠 33.60）
- 满减：246.40 ≥ 200 → 减 30，最终 216.40
- 赠品：送保温杯

### 2.3 小额订单 - 不满足满减和赠品门槛

```json
{
  "orderId": "ORD-TEST-003",
  "storeId": 101,
  "memberId": 999,
  "memberLevel": "SILVER",
  "totalAmount": 50.00,
  "items": [
    {"skuId": "SKU003", "skuName": "商品C", "quantity": 1, "unitPrice": 50.00, "subtotal": 50.00}
  ]
}
```

**预期**：
- 会员价：SILVER 不在 GOLD/PLATINUM/DIAMOND 范围内，跳过
- 折扣：50 × 0.88 = 44.00
- 满减：44 < 200，不满足
- 赠品：50 < 150，不满足
- `finalAmount` = 44.00

### 2.4 大额订单 - 测试满减互斥（活动 1003 vs 1005）

```json
{
  "orderId": "ORD-TEST-004",
  "storeId": 101,
  "memberId": 888,
  "memberLevel": "GOLD",
  "totalAmount": 500.00,
  "items": [
    {"skuId": "SKU001", "skuName": "商品A", "quantity": 3, "unitPrice": 100.00, "subtotal": 300.00},
    {"skuId": "SKU002", "skuName": "商品B", "quantity": 2, "unitPrice": 100.00, "subtotal": 200.00}
  ]
}
```

**预期**：
- 活动 1003（满200减30）与 1005（大促满300减80）互斥
- 1005 优先级 40 > 1003 优先级 30，选择 1005
- 最终应用：会员价 → 折扣 → **大促满300减80** → 赠品

### 2.5 不同门店 - 门店隔离验证

```json
{
  "orderId": "ORD-TEST-005",
  "storeId": 999,
  "memberId": 888,
  "memberLevel": "GOLD",
  "totalAmount": 280.00,
  "items": [
    {"skuId": "SKU001", "skuName": "商品A", "quantity": 2, "unitPrice": 100.00, "subtotal": 200.00},
    {"skuId": "SKU002", "skuName": "商品B", "quantity": 1, "unitPrice": 80.00, "subtotal": 80.00}
  ]
}
```

**预期**：门店 999 没有配置活动，不命中任何优惠。`finalAmount` = 280.00，`discountDetails` 为空。

### 2.6 高额满减阶梯验证

```json
{
  "orderId": "ORD-TEST-006",
  "storeId": 101,
  "memberId": null,
  "memberLevel": null,
  "totalAmount": 600.00,
  "items": [
    {"skuId": "SKU001", "skuName": "商品A", "quantity": 6, "unitPrice": 100.00, "subtotal": 600.00}
  ]
}
```

**预期**：
- 折扣：600 × 0.88 = 528.00
- 满减（如 1005 命中）：528 ≥ 300 → 减 80，最终 448.00
- 赠品：送保温杯

---

## 三、异常场景测试

### 3.1 缺少必填字段

```json
{
  "orderId": null,
  "storeId": 101,
  "totalAmount": 100.00,
  "items": []
}
```

**预期**：返回 code=400，message 包含校验错误信息。

### 3.2 金额为负数

```json
{
  "orderId": "ORD-TEST-ERR-1",
  "storeId": 101,
  "totalAmount": -10.00,
  "items": [
    {"skuId": "SKU001", "skuName": "商品A", "quantity": 1, "unitPrice": -10.00, "subtotal": -10.00}
  ]
}
```

**预期**：返回 code=400，提示金额必须大于 0。

### 3.3 查询不存在的活动

```
GET /api/v1/activity/99999999
```

**预期**：返回 code=200，data 为 null；或返回 404 业务错误码。

### 3.4 创建活动缺少规则

```json
{
  "storeId": 101,
  "name": "缺少规则的活动",
  "type": "DISCOUNT",
  "priority": 10,
  "startTime": "2026-05-01 00:00:00",
  "endTime": "2026-05-31 23:59:59",
  "ruleJson": ""
}
```

**预期**：返回 code=400，提示规则配置不能为空。

---

## 四、推荐测试流程

按以下顺序执行可覆盖完整生命周期：

1. **查询初始数据** — `GET /api/v1/activity?page=1&size=10` 确认 5 条示例数据存在
2. **计算测试 2.1** — 金卡会员全命中，验证四类活动叠加计算
3. **计算测试 2.4** — 大额订单，验证互斥裁决逻辑
4. **计算测试 2.5** — 不同门店，验证门店隔离
5. **创建活动 1.1** — 创建新满减活动，记录返回的 ID
6. **上线活动 1.8** — 将新活动上线
7. **再次计算 2.1** — 验证新活动是否生效（如果时间和门店匹配）
8. **设置灰度 1.9** — 给新活动设灰度配置
9. **下线活动 1.10** — 下线新活动
10. **删除活动 1.11** — 删除新活动
11. **异常测试** — 执行第三节全部用例
