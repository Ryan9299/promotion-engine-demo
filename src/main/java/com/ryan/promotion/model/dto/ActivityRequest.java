package com.ryan.promotion.model.dto;

import com.ryan.promotion.model.enums.PromotionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 活动创建/更新请求体。
 *
 * <p>用于 POST /api/v1/activity（创建）和 PUT /api/v1/activity/{id}（更新）接口。
 * 更新时仅修改非 null 字段，允许局部更新。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityRequest {

    /** 活动名称 */
    @NotBlank(message = "活动名称不能为空")
    private String name;

    /** 活动类型 */
    @NotNull(message = "活动类型不能为空")
    private PromotionType type;

    /**
     * 优先级，数值越大越优先参与互斥裁决，默认 0。
     */
    @Positive(message = "优先级必须大于0")
    private Integer priority;

    /**
     * 灰度配置 JSON，创建时可为空（DRAFT 状态无需灰度配置）。
     * 示例：{"storeIds":[101],"trafficPercent":50}
     */
    private String grayConfig;

    /** 活动开始时间 */
    @NotNull(message = "活动开始时间不能为空")
    private LocalDateTime startTime;

    /** 活动结束时间 */
    @NotNull(message = "活动结束时间不能为空")
    private LocalDateTime endTime;

    /**
     * 活动规则 JSON，创建时必填。
     * 格式因活动类型而异，参见 {@link com.ryan.promotion.model.entity.ActivityRule}。
     */
    @NotBlank(message = "规则配置不能为空")
    private String ruleJson;
}
