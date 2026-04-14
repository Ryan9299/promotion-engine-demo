package com.ryan.promotion.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.ryan.promotion.model.enums.ActivityStatus;
import com.ryan.promotion.model.enums.PromotionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 营销活动主表实体，对应 t_promo_activity。
 * 包含活动基本信息、类型、状态、优先级及灰度配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("activity")
public class Activity {

    /** 主键，雪花算法生成 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属门店 ID，活动按门店维度隔离 */
    private Long storeId;

    /** 活动名称 */
    private String name;

    /** 活动类型 */
    private PromotionType type;

    /** 活动状态 */
    private ActivityStatus status;

    /**
     * 优先级，数值越大越优先参与互斥裁决。
     * 同等条件下高优先级活动保留。
     */
    private Integer priority;

    /**
     * 灰度配置，JSON 格式。
     * 示例：{"storeIds":[101,102],"memberLevels":["GOLD"],"trafficPercent":50}
     */
    private String grayConfig;

    /** 活动开始时间 */
    private LocalDateTime startTime;

    /** 活动结束时间 */
    private LocalDateTime endTime;

    /** 创建时间，由数据库自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间，由数据库自动填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除标志：0=正常，1=已删除 */
    @TableLogic
    private Integer deleted;
}
