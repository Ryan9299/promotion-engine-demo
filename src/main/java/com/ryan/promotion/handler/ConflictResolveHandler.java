package com.ryan.promotion.handler;

import com.ryan.promotion.mapper.ActivityConflictMapper;
import com.ryan.promotion.model.entity.Activity;
import com.ryan.promotion.model.entity.ActivityConflict;
import com.ryan.promotion.model.enums.ConflictRelation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 责任链第2步：互斥冲突解决处理器。
 *
 * <p>采用<b>贪心算法</b>解决活动之间的互斥关系：
 * <ol>
 *   <li>以 {@code matchedActivities}（已按优先级降序排列）为输入。</li>
 *   <li>从高优先级活动开始逐一考察，检查其与"已选集合"中任意活动是否存在 EXCLUSIVE 关系。</li>
 *   <li>若存在互斥，丢弃当前活动（保留高优先级）；否则加入已选集合。</li>
 *   <li>最终已选集合即为 {@code resolvedActivities}。</li>
 * </ol>
 *
 * <p>COMPATIBLE 关系的记录不影响选择结果（显式兼容等同于无配置）。
 * 未在冲突表中出现的两个活动对，默认视为<b>兼容</b>，可叠加。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConflictResolveHandler extends PromotionHandler {

    private final ActivityConflictMapper activityConflictMapper;

    /**
     * 执行互斥冲突解决，填充 resolvedActivities，然后传递给下一节点。
     */
    @Override
    public void handle(PromotionContext context) {
        List<Activity> matched = context.getMatchedActivities();

        if (matched.size() <= 1) {
            // 0 或 1 个活动，无需冲突检测
            context.setResolvedActivities(new ArrayList<>(matched));
            passToNext(context);
            return;
        }

        // 构建互斥关系邻接表：key=activityId, value=与其互斥的所有活动ID集合
        Map<Long, Set<Long>> exclusiveMap = buildExclusiveMap(matched);

        // 贪心选择：matchedActivities 已按优先级降序排列
        List<Activity> resolved = new ArrayList<>();
        for (Activity candidate : matched) {
            boolean conflicted = resolved.stream().anyMatch(selected ->
                    exclusiveMap.getOrDefault(candidate.getId(), Set.of())
                            .contains(selected.getId())
            );
            if (conflicted) {
                log.debug("活动[{}]与已选活动存在互斥关系，丢弃", candidate.getId());
            } else {
                resolved.add(candidate);
            }
        }

        log.debug("冲突解决后活动数量：{}/{}", resolved.size(), matched.size());
        context.setResolvedActivities(resolved);
        passToNext(context);
    }

    // ---------------------------------------------------------------
    // 私有方法
    // ---------------------------------------------------------------

    /**
     * 查询活动集合内的互斥关系，构建双向邻接表。
     * 若两个活动存在 EXCLUSIVE 关系，双向均写入 map，方便 O(1) 查询。
     */
    private Map<Long, Set<Long>> buildExclusiveMap(List<Activity> activities) {
        List<Long> ids = activities.stream().map(Activity::getId).collect(Collectors.toList());
        List<ActivityConflict> conflicts = activityConflictMapper.selectConflictsByActivityIds(ids);

        Map<Long, Set<Long>> exclusiveMap = new HashMap<>();
        for (ActivityConflict conflict : conflicts) {
            if (conflict.getRelation() != ConflictRelation.EXCLUSIVE) {
                continue;
            }
            // 双向写入，查询时只需查自身 key
            exclusiveMap.computeIfAbsent(conflict.getActivityIdA(), k -> new HashSet<>())
                    .add(conflict.getActivityIdB());
            exclusiveMap.computeIfAbsent(conflict.getActivityIdB(), k -> new HashSet<>())
                    .add(conflict.getActivityIdA());
        }
        return exclusiveMap;
    }
}
