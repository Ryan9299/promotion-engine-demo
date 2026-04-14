package com.ryan.promotion.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 活动规则表实体，对应 t_promo_activity_rule。
 * rule_json 字段存储各活动类型的具体规则参数，结构因类型而异：
 * <ul>
 *   <li>FULL_REDUCTION：{tiers:[{threshold,reduction}]}</li>
 *   <li>DISCOUNT：{discountRate, minOrderAmount}</li>
 *   <li>GIFT：{minOrderAmount, giftSkuId, giftSkuName, giftQuantity}</li>
 *   <li>MEMBER_PRICE：{memberLevels:[], discountRate}</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_promo_activity_rule")
public class ActivityRule {

    /** 主键，雪花算法生成 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联活动 ID */
    private Long activityId;

    /** 规则参数 JSON */
    private String ruleJson;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除标志 */
    @TableLogic
    private Integer deleted;
}
