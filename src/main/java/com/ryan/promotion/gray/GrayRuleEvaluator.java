package com.ryan.promotion.gray;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ryan.promotion.model.dto.OrderContext;
import com.ryan.promotion.model.entity.Activity;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

/**
 * 灰度规则评估器，判断某个 GRAY 状态的活动是否对当前请求生效。
 *
 * <p>支持三种灰度维度（AND 关系，所有指定条件均须满足）：
 * <ol>
 *   <li><b>storeIds</b>：指定门店白名单，{@code orderContext.storeId} 须在列表内。</li>
 *   <li><b>memberLevels</b>：指定会员等级白名单，{@code orderContext.memberLevel} 须在列表内。</li>
 *   <li><b>trafficPercent</b>：流量百分比（0~100），对 (activityId XOR userId) 取模，
 *       结果 &lt; trafficPercent 则命中。不同活动对同一用户路由结果独立。</li>
 * </ol>
 *
 * <p>若 {@code grayConfig} 为空或三个维度均未配置，视为全量放开，直接返回 {@code true}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrayRuleEvaluator {

    private final ObjectMapper objectMapper;

    /**
     * 判断指定活动是否对当前订单请求生效。
     * 仅在 {@code Activity.status == GRAY} 时需调用；ACTIVE 活动无需评估。
     *
     * @param activity     待评估的灰度活动
     * @param orderContext 当前订单上下文
     * @return {@code true} 表示命中灰度，活动对本次请求生效
     */
    public boolean evaluate(Activity activity, OrderContext orderContext) {
        String grayConfig = activity.getGrayConfig();
        if (!StringUtils.hasText(grayConfig)) {
            // 无灰度配置，全量放开
            return true;
        }

        GrayRule rule;
        try {
            rule = objectMapper.readValue(grayConfig, GrayRule.class);
        } catch (Exception e) {
            log.warn("活动[{}]灰度配置解析失败，默认不命中: {}", activity.getId(), e.getMessage());
            return false;
        }

        // 维度1：门店白名单
        if (!rule.getStoreIds().isEmpty()
                && !rule.getStoreIds().contains(orderContext.getStoreId())) {
            log.debug("活动[{}]门店不在灰度白名单，storeId={}", activity.getId(), orderContext.getStoreId());
            return false;
        }

        // 维度2：会员等级白名单
        if (!rule.getMemberLevels().isEmpty()
                && !rule.getMemberLevels().contains(orderContext.getMemberLevel())) {
            log.debug("活动[{}]会员等级不在灰度白名单，memberLevel={}",
                    activity.getId(), orderContext.getMemberLevel());
            return false;
        }

        // 维度3：流量百分比（哈希取模）
        if (rule.getTrafficPercent() != null && rule.getTrafficPercent() < 100) {
            long hashBase = orderContext.getMemberId() != null
                    ? orderContext.getMemberId()
                    : orderContext.getStoreId();
            // XOR activityId 保证不同活动对同一用户路由结果独立
            int slot = (int) (Math.abs(hashBase ^ activity.getId()) % 100);
            if (slot >= rule.getTrafficPercent()) {
                log.debug("活动[{}]流量未命中灰度，slot={}, trafficPercent={}",
                        activity.getId(), slot, rule.getTrafficPercent());
                return false;
            }
        }

        return true;
    }

    // ---------------------------------------------------------------
    // 灰度规则 POJO
    // ---------------------------------------------------------------

    /**
     * 灰度规则配置，对应 Activity.grayConfig 的 JSON 结构。
     */
    @Data
    static class GrayRule {

        /** 生效门店 ID 白名单，为空表示不限门店 */
        private List<Long> storeIds = Collections.emptyList();

        /** 生效会员等级白名单，如 ["GOLD","PLATINUM"]，为空表示不限等级 */
        private List<String> memberLevels = Collections.emptyList();

        /**
         * 流量百分比，取值 0~100，为 null 或 100 表示全量放开。
         * 计算方式：abs(userId XOR activityId) % 100 &lt; trafficPercent 则命中。
         */
        private Integer trafficPercent;
    }
}
