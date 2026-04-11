# Promotion Engine Demo

## 项目概述

简化版营销活动叠加引擎，面向连锁零售场景，支持满减、折扣、赠品、会员等级价四种活动类型任意组合。
用于技术展示和面试项目佐证，开源在个人 GitHub。

## 技术栈

- Java 17+
- Spring Boot 3.x
- Maven
- MyBatis-Plus（数据层）
- MySQL 8.x
- Redis 6.x（分布式缓存 + 幂等）
- Caffeine（本地缓存）
- Lombok
- JUnit 5 + Mockito（测试）

## 项目结构规范

```
promotion-engine/
├── pom.xml
├── CLAUDE.md
├── README.md
├── sql/
│   └── init.sql                          # 建表语句+示例数据
├── src/main/java/com/ryan/promotion/
│   ├── PromotionEngineApplication.java
│   ├── config/
│   │   ├── RedisConfig.java
│   │   └── CaffeineConfig.java
│   ├── model/
│   │   ├── entity/                       # 数据库实体
│   │   │   ├── Activity.java
│   │   │   ├── ActivityRule.java
│   │   │   └── ActivityConflict.java
│   │   ├── enums/
│   │   │   ├── PromotionType.java        # FULL_REDUCTION / DISCOUNT / GIFT / MEMBER_PRICE
│   │   │   ├── ConflictRelation.java     # EXCLUSIVE / COMPATIBLE
│   │   │   └── ActivityStatus.java       # DRAFT / GRAY / ACTIVE / EXPIRED
│   │   ├── dto/
│   │   │   ├── OrderContext.java         # 输入：订单信息、商品列表、会员信息、门店ID
│   │   │   └── CalcResult.java           # 输出：优惠明细、最终金额、赠品列表
│   │   └── vo/
│   │       └── PromotionResultVO.java    # API 响应
│   ├── strategy/
│   │   ├── PromotionStrategy.java        # 策略接口
│   │   ├── FullReductionStrategy.java
│   │   ├── DiscountStrategy.java
│   │   ├── GiftStrategy.java
│   │   └── MemberPriceStrategy.java
│   ├── handler/                          # 责任链
│   │   ├── PromotionHandler.java         # 抽象基类
│   │   ├── ActivityFilterHandler.java    # 第1步：筛选命中活动+灰度判断
│   │   ├── ConflictResolveHandler.java   # 第2步：互斥冲突检测
│   │   ├── CalcHandler.java             # 第3步：调用策略计算
│   │   └── ResultAssembleHandler.java   # 第4步：组装结果
│   ├── chain/
│   │   └── PromotionChainBuilder.java    # 构建责任链
│   ├── cache/
│   │   └── PromotionCacheManager.java    # Caffeine + Redis 两级缓存
│   ├── gray/
│   │   └── GrayRuleEvaluator.java        # 灰度规则判断
│   ├── service/
│   │   ├── PromotionService.java         # 对外入口
│   │   └── ActivityManageService.java    # 活动CRUD+缓存失效
│   ├── mapper/
│   │   ├── ActivityMapper.java
│   │   ├── ActivityRuleMapper.java
│   │   └── ActivityConflictMapper.java
│   └── controller/
│       ├── PromotionController.java      # 结算计算 API
│       └── ActivityController.java       # 活动管理 API（CRUD+灰度+上下线）
└── src/test/java/com/ryan/promotion/
    ├── strategy/                         # 各策略单元测试
    ├── handler/                          # 责任链各节点测试
    ├── cache/                            # 缓存命中/穿透/一致性测试
    └── integration/                      # 端到端集成测试
```

## 编码规范

### 通用规则
- 所有类必须有 Javadoc 类注释，说明用途
- 方法注释用中文，代码用英文命名
- 不使用魔法值，常量统一定义在对应的 enum 或 constants 类中
- 使用 Lombok 的 @Data / @Builder / @AllArgsConstructor，不手写 getter/setter
- 异常使用自定义 BusinessException(code, message) 统一抛出

### 设计模式使用要求
- **策略模式**：每个 PromotionStrategy 实现类通过 @Component 注入 Spring 容器，PromotionService 启动时通过 `List<PromotionStrategy>` 自动收集，按 getType() 建立 Map 索引
- **责任链模式**：PromotionHandler 为抽象类，包含 next 指针和 passToNext() 方法；PromotionChainBuilder 负责按固定顺序组装链；链节点之间通过 PromotionContext 传递数据
- **PromotionContext**：贯穿整条链的上下文对象，包含 orderContext（输入）、matchedActivities（筛选结果）、resolvedActivities（冲突解决后）、calcResults（计算结果）、finalResult（最终输出）

### 缓存规范
- Caffeine：maximumSize=1000，expireAfterWrite=30s，使用 LoadingCache 自动回源 Redis
- Redis：key 前缀 `promo:`，活动规则 key 为 `promo:rules:{storeId}`，TTL=5min
- 缓存更新：写 DB → 删 Redis key → Redis Pub/Sub 通知各实例失效 Caffeine
- Pub/Sub channel：`promo:cache:invalidate`

### 灰度规范
- 灰度配置存在 Activity 实体的 grayConfig 字段（JSON 格式）
- 支持三种灰度维度：指定门店ID列表、会员等级范围、流量百分比（哈希取模）
- GrayRuleEvaluator 为独立组件，在 ActivityFilterHandler 中调用

### 数据库规范
- 表名前缀 `t_promo_`
- 主键使用雪花算法 Long 类型
- 通用字段：id / create_time / update_time / deleted（逻辑删除）
- Activity 表核心字段：name / type / status / priority / gray_config / start_time / end_time
- ActivityRule 表核心字段：activity_id / rule_json（JSON存规则参数）
- ActivityConflict 表核心字段：activity_id_a / activity_id_b / relation

### API 规范
- 统一响应包装：`Result<T>` 包含 code / message / data
- 结算接口：POST `/api/v1/promotion/calculate`，入参 OrderContext，出参 PromotionResultVO
- 活动管理：标准 RESTful CRUD，PUT `/api/v1/activity/{id}/publish` 上线，PUT `/{id}/gray` 设灰度

### 测试规范
- 每个 Strategy 至少 3 个测试用例（正常/边界/不满足条件）
- ConflictResolveHandler 需覆盖：全兼容、全互斥、部分互斥场景
- 缓存测试需覆盖：L1命中、L1未命中L2命中、双未命中回源、缓存失效后重新加载
- 集成测试：模拟一笔订单命中多个活动的完整计算流程

## 构建与运行

```bash
# 构建
mvn clean package -DskipTests

# 运行（需提前启动 MySQL 和 Redis）
java -jar target/promotion-engine-1.0.0.jar

# 测试
mvn test
```

## 关键实现提示

1. 计算顺序：会员价 → 折扣 → 满减 → 赠品（会员价改基准价，折扣基于会员价，满减基于折后价）
2. 互斥检测用贪心：按优先级排序后遍历，每加入一个活动检查与已选集合的互斥关系
3. 批量出库的 Lua 脚本设上限 50，超出拆分调用
4. 所有金额使用 BigDecimal，禁止 double/float
5. Redis Pub/Sub 监听器在 @PostConstruct 中初始化
