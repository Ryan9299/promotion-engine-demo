package com.ryan.promotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ryan.promotion.cache.CacheInvalidateEvent;
import com.ryan.promotion.common.exception.BusinessException;
import com.ryan.promotion.mapper.ActivityConflictMapper;
import com.ryan.promotion.mapper.ActivityMapper;
import com.ryan.promotion.mapper.ActivityRuleMapper;
import com.ryan.promotion.model.dto.ActivityRequest;
import com.ryan.promotion.model.entity.Activity;
import com.ryan.promotion.model.entity.ActivityConflict;
import com.ryan.promotion.model.entity.ActivityRule;
import com.ryan.promotion.model.enums.ActivityStatus;
import com.ryan.promotion.model.enums.PromotionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 活动管理服务，提供活动的完整生命周期管理。
 *
 * <p>状态流转：
 * <pre>
 *   DRAFT → ACTIVE（publish）
 *   DRAFT → GRAY（updateGray）
 *   ACTIVE / GRAY → EXPIRED（offline）
 * </pre>
 *
 * <p>凡涉及活动数据变更的操作，均通过发布 {@link CacheInvalidateEvent} 事件，
 * 由 {@link com.ryan.promotion.cache.CacheInvalidateListener} 在事务提交后清除全量两级缓存，
 * 确保 DB 变更落地后才失效缓存，避免事务回滚导致的缓存不一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityManageService {

    private final ActivityMapper activityMapper;
    private final ActivityRuleMapper activityRuleMapper;
    private final ActivityConflictMapper activityConflictMapper;
    private final ApplicationEventPublisher eventPublisher;

    // ---------------------------------------------------------------
    // 查询
    // ---------------------------------------------------------------

    /**
     * 按 ID 查询活动，不存在则抛出业务异常。
     *
     * @param id 活动 ID
     * @return 活动实体
     */
    public Activity getById(Long id) {
        Activity activity = activityMapper.selectById(id);
        if (activity == null) {
            throw new BusinessException(4040, "活动不存在：" + id);
        }
        return activity;
    }

    /**
     * 分页查询活动列表，支持按状态和类型过滤，按优先级降序、创建时间降序排列。
     *
     * @param page   页码（从 1 开始）
     * @param size   每页条数
     * @param status 状态过滤，null 表示不过滤
     * @param type   类型过滤，null 表示不过滤
     * @return 分页结果
     */
    public IPage<Activity> list(int page, int size, ActivityStatus status, PromotionType type) {
        LambdaQueryWrapper<Activity> wrapper = new LambdaQueryWrapper<Activity>()
                .eq(status != null, Activity::getStatus, status)
                .eq(type != null, Activity::getType, type)
                .orderByDesc(Activity::getPriority)
                .orderByDesc(Activity::getCreateTime);
        return activityMapper.selectPage(new Page<>(page, size), wrapper);
    }

    // ---------------------------------------------------------------
    // 创建
    // ---------------------------------------------------------------

    /**
     * 创建活动及其规则，初始状态为 DRAFT。
     * Activity 和 ActivityRule 在同一事务内写入。
     *
     * @param req 活动创建请求
     * @return 创建成功的活动实体（含雪花 ID）
     */
    @Transactional(rollbackFor = Exception.class)
    public Activity create(ActivityRequest req) {
        Activity activity = Activity.builder()
                .storeId(req.getStoreId())
                .name(req.getName())
                .type(req.getType())
                .status(ActivityStatus.DRAFT)
                .priority(req.getPriority() != null ? req.getPriority() : 0)
                .grayConfig(req.getGrayConfig())
                .startTime(req.getStartTime())
                .endTime(req.getEndTime())
                .build();
        activityMapper.insert(activity);

        ActivityRule rule = ActivityRule.builder()
                .activityId(activity.getId())
                .ruleJson(req.getRuleJson())
                .build();
        activityRuleMapper.insert(rule);

        log.info("活动创建成功：id={}, name={}, type={}", activity.getId(), activity.getName(), activity.getType());
        return activity;
    }

    // ---------------------------------------------------------------
    // 更新
    // ---------------------------------------------------------------

    /**
     * 更新活动基本信息（不含状态变更，不含灰度配置）。
     * 若 req.ruleJson 非空，则同步更新对应规则。
     * 更新成功后失效全量缓存。
     *
     * @param id  活动 ID
     * @param req 更新请求（字段为 null 时不修改）
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ActivityRequest req) {
        getById(id); // 校验存在

        // 构建部分更新实体（MyBatis-Plus updateById 仅更新非 null 字段）
        Activity update = Activity.builder()
                .id(id)
                .name(req.getName())
                .type(req.getType())
                .priority(req.getPriority())
                .grayConfig(req.getGrayConfig())
                .startTime(req.getStartTime())
                .endTime(req.getEndTime())
                .build();
        activityMapper.updateById(update);

        // 同步更新规则（若提供）
        if (StringUtils.hasText(req.getRuleJson())) {
            activityRuleMapper.update(null,
                    new LambdaUpdateWrapper<ActivityRule>()
                            .eq(ActivityRule::getActivityId, id)
                            .set(ActivityRule::getRuleJson, req.getRuleJson()));
        }

        eventPublisher.publishEvent(CacheInvalidateEvent.all(this));
        log.info("活动更新成功：id={}", id);
    }

    // ---------------------------------------------------------------
    // 删除
    // ---------------------------------------------------------------

    /**
     * 逻辑删除活动及其规则、冲突关系，失效全量缓存。
     *
     * @param id 活动 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        getById(id); // 校验存在

        activityMapper.deleteById(id);

        // 同步逻辑删除规则
        activityRuleMapper.delete(
                new LambdaQueryWrapper<ActivityRule>().eq(ActivityRule::getActivityId, id));

        // 同步删除冲突配置（双向）
        activityConflictMapper.delete(
                new LambdaQueryWrapper<ActivityConflict>()
                        .eq(ActivityConflict::getActivityIdA, id)
                        .or()
                        .eq(ActivityConflict::getActivityIdB, id));

        eventPublisher.publishEvent(CacheInvalidateEvent.all(this));
        log.info("活动删除成功：id={}", id);
    }

    // ---------------------------------------------------------------
    // 状态变更
    // ---------------------------------------------------------------

    /**
     * 将活动上线，状态变更为 ACTIVE，失效全量缓存。
     * 适用于 DRAFT 或 GRAY 状态的活动。
     *
     * @param id 活动 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long id) {
        Activity activity = getById(id);
        if (activity.getStatus() == ActivityStatus.ACTIVE) {
            log.info("活动[{}]已是 ACTIVE 状态，无需重复上线", id);
            return;
        }
        activityMapper.update(null,
                new LambdaUpdateWrapper<Activity>()
                        .eq(Activity::getId, id)
                        .set(Activity::getStatus, ActivityStatus.ACTIVE));
        eventPublisher.publishEvent(CacheInvalidateEvent.all(this));
        log.info("活动上线成功：id={}", id);
    }

    /**
     * 将活动下线，状态变更为 EXPIRED，失效全量缓存。
     *
     * @param id 活动 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void offline(Long id) {
        getById(id);
        activityMapper.update(null,
                new LambdaUpdateWrapper<Activity>()
                        .eq(Activity::getId, id)
                        .set(Activity::getStatus, ActivityStatus.EXPIRED));
        eventPublisher.publishEvent(CacheInvalidateEvent.all(this));
        log.info("活动下线成功：id={}", id);
    }

    /**
     * 更新灰度配置，同时将状态置为 GRAY（自动转入灰度发布）。
     * 失效全量缓存后，新灰度规则将在下次请求时生效。
     *
     * @param id         活动 ID
     * @param grayConfig 灰度配置 JSON
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateGray(Long id, String grayConfig) {
        getById(id);
        activityMapper.update(null,
                new LambdaUpdateWrapper<Activity>()
                        .eq(Activity::getId, id)
                        .set(Activity::getStatus, ActivityStatus.GRAY)
                        .set(Activity::getGrayConfig, grayConfig));
        eventPublisher.publishEvent(CacheInvalidateEvent.all(this));
        log.info("灰度配置更新成功：id={}", id);
    }
}
