package com.ryan.promotion.handler;

import com.ryan.promotion.model.dto.CalcResult;
import com.ryan.promotion.model.dto.OrderContext;
import com.ryan.promotion.model.entity.Activity;
import com.ryan.promotion.model.entity.ActivityRule;
import com.ryan.promotion.model.vo.PromotionResultVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 责任链贯穿上下文，封装整条链路所有中间状态与最终输出。
 *
 * <p>生命周期与一次促销计算请求绑定，每次请求创建新实例，线程安全由调用方保证。
 *
 * <p>数据流向：
 * <pre>
 *   orderContext（输入）
 *       ↓ ActivityFilterHandler
 *   matchedActivities + ruleMap（筛选后活动及规则）
 *       ↓ ConflictResolveHandler
 *   resolvedActivities（冲突解决后活动）
 *       ↓ CalcHandler
 *   calcResults（各活动计算结果）
 *       ↓ ResultAssembleHandler
 *   finalResult（最终输出）
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionContext {

    // ---------------------------------------------------------------
    // 输入
    // ---------------------------------------------------------------

    /** 订单上下文（原始输入，链路中不修改此字段引用） */
    private OrderContext orderContext;

    // ---------------------------------------------------------------
    // 中间结果：ActivityFilterHandler 填充
    // ---------------------------------------------------------------

    /**
     * 命中当前请求的活动列表（已通过时间/状态/灰度过滤），按优先级降序排列。
     */
    @Builder.Default
    private List<Activity> matchedActivities = new ArrayList<>();

    /**
     * 活动规则 Map，key=activityId，value=ActivityRule。
     * 与 matchedActivities 同步填充，供后续 Handler 按 ID 快速查找规则。
     */
    @Builder.Default
    private Map<Long, ActivityRule> ruleMap = new HashMap<>();

    // ---------------------------------------------------------------
    // 中间结果：ConflictResolveHandler 填充
    // ---------------------------------------------------------------

    /**
     * 冲突解决后保留的活动列表，已去除互斥冲突中低优先级的活动。
     * 此列表内的活动将全部参与价格计算。
     */
    @Builder.Default
    private List<Activity> resolvedActivities = new ArrayList<>();

    // ---------------------------------------------------------------
    // 中间结果：CalcHandler 填充
    // ---------------------------------------------------------------

    /**
     * 各活动的计算结果列表，顺序与计算顺序一致：
     * MEMBER_PRICE → DISCOUNT → FULL_REDUCTION → GIFT。
     */
    @Builder.Default
    private List<CalcResult> calcResults = new ArrayList<>();

    // ---------------------------------------------------------------
    // 最终输出：ResultAssembleHandler 填充
    // ---------------------------------------------------------------

    /** 组装完成的最终促销结果，作为 API 响应体 */
    private PromotionResultVO finalResult;
}
