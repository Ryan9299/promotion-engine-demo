-- ============================================================
-- Promotion Engine Demo — 初始化 DDL + 示例数据
-- 数据库：promotion_engine
-- 编码：utf8mb4
-- ============================================================

CREATE DATABASE IF NOT EXISTS promotion_engine
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE promotion_engine;

-- ------------------------------------------------------------
-- 1. 活动主表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_promo_activity;
CREATE TABLE t_promo_activity
(
    id          BIGINT       NOT NULL COMMENT '主键（雪花算法）',
    name        VARCHAR(128) NOT NULL COMMENT '活动名称',
    type        VARCHAR(32)  NOT NULL COMMENT '活动类型：FULL_REDUCTION/DISCOUNT/GIFT/MEMBER_PRICE',
    status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/GRAY/ACTIVE/EXPIRED',
    priority    INT          NOT NULL DEFAULT 0 COMMENT '优先级，数值越大越优先参与互斥裁决',
    gray_config TEXT                  COMMENT '灰度配置 JSON：storeIds / memberLevels / trafficPercent',
    start_time  DATETIME     NOT NULL COMMENT '活动开始时间',
    end_time    DATETIME     NOT NULL COMMENT '活动结束时间',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除',
    PRIMARY KEY (id),
    INDEX idx_status_time (status, start_time, end_time),
    INDEX idx_type (type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '营销活动主表';

-- ------------------------------------------------------------
-- 2. 活动规则表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_promo_activity_rule;
CREATE TABLE t_promo_activity_rule
(
    id          BIGINT   NOT NULL COMMENT '主键（雪花算法）',
    activity_id BIGINT   NOT NULL COMMENT '关联活动ID',
    rule_json   TEXT     NOT NULL COMMENT '规则参数 JSON（不同类型结构不同）',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    INDEX idx_activity_id (activity_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '活动规则表，rule_json 存储各类型规则参数';

-- ------------------------------------------------------------
-- 3. 活动冲突关系表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS t_promo_activity_conflict;
CREATE TABLE t_promo_activity_conflict
(
    id           BIGINT      NOT NULL COMMENT '主键（雪花算法）',
    activity_id_a BIGINT     NOT NULL COMMENT '活动A ID',
    activity_id_b BIGINT     NOT NULL COMMENT '活动B ID',
    relation     VARCHAR(16) NOT NULL COMMENT '关系类型：EXCLUSIVE=互斥 / COMPATIBLE=兼容',
    create_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_activity_pair (activity_id_a, activity_id_b),
    INDEX idx_activity_a (activity_id_a),
    INDEX idx_activity_b (activity_id_b)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '活动互斥/兼容关系表';

-- ============================================================
-- 示例数据（覆盖四种活动类型 + 一组互斥关系）
-- ID 使用固定值便于演示，生产环境由雪花算法生成
-- ============================================================

-- 活动1：会员等级价（MEMBER_PRICE）—— 金卡及以上享9折会员价
INSERT INTO t_promo_activity (id, name, type, status, priority, gray_config, start_time, end_time)
VALUES (1001, '金卡会员专属价', 'MEMBER_PRICE', 'ACTIVE', 10, NULL,
        '2026-01-01 00:00:00', '2026-12-31 23:59:59');

INSERT INTO t_promo_activity_rule (id, activity_id, rule_json)
VALUES (2001, 1001,
        '{"memberLevels": ["GOLD", "PLATINUM", "DIAMOND"], "discountRate": 0.90}');

-- 活动2：折扣（DISCOUNT）—— 全场8.8折
INSERT INTO t_promo_activity (id, name, type, status, priority, gray_config, start_time, end_time)
VALUES (1002, '全场8.8折优惠', 'DISCOUNT', 'ACTIVE', 20, NULL,
        '2026-04-01 00:00:00', '2026-04-30 23:59:59');

INSERT INTO t_promo_activity_rule (id, activity_id, rule_json)
VALUES (2002, 1002,
        '{"discountRate": 0.88, "minOrderAmount": 0}');

-- 活动3：满减（FULL_REDUCTION）—— 满200减30
INSERT INTO t_promo_activity (id, name, type, status, priority, gray_config, start_time, end_time)
VALUES (1003, '满200减30', 'FULL_REDUCTION', 'ACTIVE', 30, NULL,
        '2026-04-01 00:00:00', '2026-04-30 23:59:59');

INSERT INTO t_promo_activity_rule (id, activity_id, rule_json)
VALUES (2003, 1003,
        '{"tiers": [{"threshold": 200, "reduction": 30}, {"threshold": 500, "reduction": 100}]}');

-- 活动4：赠品（GIFT）—— 购满150送定制保温杯
INSERT INTO t_promo_activity (id, name, type, status, priority, gray_config, start_time, end_time)
VALUES (1004, '满150送保温杯', 'GIFT', 'ACTIVE', 5,
        '{"storeIds": [101, 102, 103], "trafficPercent": 100}',
        '2026-04-01 00:00:00', '2026-04-30 23:59:59');

INSERT INTO t_promo_activity_rule (id, activity_id, rule_json)
VALUES (2004, 1004,
        '{"minOrderAmount": 150, "gifts": [{"giftSkuId": "SKU_CUP_001", "giftSkuName": "定制保温杯", "giftQuantity": 1, "marketPrice": 49.90}]}');

-- 活动5：满减（FULL_REDUCTION）—— 大促专属满300减80（与活动3互斥）
INSERT INTO t_promo_activity (id, name, type, status, priority, gray_config, start_time, end_time)
VALUES (1005, '大促满300减80', 'FULL_REDUCTION', 'ACTIVE', 40,
        '{"trafficPercent": 50}',
        '2026-04-10 00:00:00', '2026-04-20 23:59:59');

INSERT INTO t_promo_activity_rule (id, activity_id, rule_json)
VALUES (2005, 1005,
        '{"tiers": [{"threshold": 300, "reduction": 80}, {"threshold": 600, "reduction": 180}]}');

-- ------------------------------------------------------------
-- 冲突关系：活动3（满200减30）与活动5（大促满300减80）互斥
-- 两个满减活动不可同时叠加，优先级高的（1005）优先生效
-- ------------------------------------------------------------
INSERT INTO t_promo_activity_conflict (id, activity_id_a, activity_id_b, relation)
VALUES (3001, 1003, 1005, 'EXCLUSIVE');
