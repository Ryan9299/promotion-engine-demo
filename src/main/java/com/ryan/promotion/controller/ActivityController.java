package com.ryan.promotion.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ryan.promotion.common.Result;
import com.ryan.promotion.model.dto.ActivityRequest;
import com.ryan.promotion.model.entity.Activity;
import com.ryan.promotion.model.enums.ActivityStatus;
import com.ryan.promotion.model.enums.PromotionType;
import com.ryan.promotion.service.ActivityManageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 活动管理接口，提供活动全生命周期的 RESTful API。
 *
 * <pre>
 * POST   /api/v1/activity              创建活动
 * GET    /api/v1/activity              分页查询活动列表
 * GET    /api/v1/activity/{id}         查询单个活动
 * PUT    /api/v1/activity/{id}         更新活动
 * DELETE /api/v1/activity/{id}         删除活动（逻辑删除）
 * PUT    /api/v1/activity/{id}/publish 上线活动（→ ACTIVE）
 * PUT    /api/v1/activity/{id}/offline 下线活动（→ EXPIRED）
 * PUT    /api/v1/activity/{id}/gray    更新灰度配置（→ GRAY）
 * </pre>
 */
@Validated
@RestController
@RequestMapping("/api/v1/activity")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityManageService activityManageService;

    // ---------------------------------------------------------------
    // 查询
    // ---------------------------------------------------------------

    /**
     * 按 ID 查询活动详情。
     */
    @GetMapping("/{id}")
    public Result<Activity> getById(@PathVariable Long id) {
        return Result.ok(activityManageService.getById(id));
    }

    /**
     * 分页查询活动列表，支持按状态和类型过滤。
     *
     * @param page   页码，默认 1
     * @param size   每页条数，默认 20
     * @param status 状态过滤（可选）：DRAFT / GRAY / ACTIVE / EXPIRED
     * @param type   类型过滤（可选）：FULL_REDUCTION / DISCOUNT / GIFT / MEMBER_PRICE
     */
    @GetMapping
    public Result<IPage<Activity>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) ActivityStatus status,
            @RequestParam(required = false) PromotionType type) {
        return Result.ok(activityManageService.list(page, size, status, type));
    }

    // ---------------------------------------------------------------
    // 创建
    // ---------------------------------------------------------------

    /**
     * 创建活动，初始状态为 DRAFT。
     * 同时写入活动规则（ruleJson 必填）。
     */
    @PostMapping
    public Result<Activity> create(@Valid @RequestBody ActivityRequest req) {
        return Result.ok(activityManageService.create(req));
    }

    // ---------------------------------------------------------------
    // 更新
    // ---------------------------------------------------------------

    /**
     * 更新活动基本信息（名称、规则、时间等），状态变更请使用专用端点。
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody ActivityRequest req) {
        activityManageService.update(id, req);
        return Result.ok();
    }

    // ---------------------------------------------------------------
    // 删除
    // ---------------------------------------------------------------

    /**
     * 逻辑删除活动，同步清除规则和冲突配置。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        activityManageService.delete(id);
        return Result.ok();
    }

    // ---------------------------------------------------------------
    // 状态变更
    // ---------------------------------------------------------------

    /**
     * 将活动上线（状态变更为 ACTIVE），触发全量缓存失效。
     */
    @PutMapping("/{id}/publish")
    public Result<Void> publish(@PathVariable Long id) {
        activityManageService.publish(id);
        return Result.ok();
    }

    /**
     * 将活动下线（状态变更为 EXPIRED），触发全量缓存失效。
     */
    @PutMapping("/{id}/offline")
    public Result<Void> offline(@PathVariable Long id) {
        activityManageService.offline(id);
        return Result.ok();
    }

    /**
     * 更新灰度配置，活动状态自动转为 GRAY，触发全量缓存失效。
     *
     * <p>请求体示例：
     * <pre>{@code
     * {
     *   "grayConfig": "{\"storeIds\":[101,102],\"trafficPercent\":50}"
     * }
     * }</pre>
     */
    @PutMapping("/{id}/gray")
    public Result<Void> updateGray(@PathVariable Long id,
                                   @Valid @RequestBody UpdateGrayRequest req) {
        activityManageService.updateGray(id, req.getGrayConfig());
        return Result.ok();
    }

    // ---------------------------------------------------------------
    // 内部请求体
    // ---------------------------------------------------------------

    /**
     * 更新灰度配置的请求体。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateGrayRequest {

        /** 灰度配置 JSON，如 {"storeIds":[101],"trafficPercent":50} */
        @NotBlank(message = "灰度配置不能为空")
        private String grayConfig;
    }
}
