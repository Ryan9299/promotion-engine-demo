package com.ryan.promotion.handler;

import com.ryan.promotion.mapper.ActivityConflictMapper;
import com.ryan.promotion.model.entity.Activity;
import com.ryan.promotion.model.entity.ActivityConflict;
import com.ryan.promotion.model.enums.ActivityStatus;
import com.ryan.promotion.model.enums.ConflictRelation;
import com.ryan.promotion.model.enums.PromotionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * ConflictResolveHandler 单元测试。
 * 覆盖：全兼容、全互斥、部分互斥三种场景。
 */
@DisplayName("ConflictResolveHandler 单元测试")
@ExtendWith(MockitoExtension.class)
class ConflictResolveHandlerTest {

    @Mock
    private ActivityConflictMapper activityConflictMapper;

    @InjectMocks
    private ConflictResolveHandler handler;

    @BeforeEach
    void setUp() {
        // 不挂载后续节点：ConflictResolveHandler 的测试只关注 resolvedActivities，
        // passToNext 在 next==null 时为空操作，不影响断言。
    }

    // ------------------------------------------------------------------
    // 全兼容
    // ------------------------------------------------------------------

    @Test
    @DisplayName("全兼容（无互斥记录）→ 所有活动均保留")
    void handle_allCompatible_allActivitiesResolved() {
        Activity a1 = activity(1001L, PromotionType.MEMBER_PRICE, 10);
        Activity a2 = activity(1002L, PromotionType.DISCOUNT,      20);
        Activity a3 = activity(1003L, PromotionType.FULL_REDUCTION, 30);

        // 无任何冲突记录
        when(activityConflictMapper.selectConflictsByActivityIds(anyList()))
                .thenReturn(List.of());

        PromotionContext ctx = contextWith(List.of(a3, a2, a1)); // 按优先级降序
        handler.handle(ctx);

        assertThat(ctx.getResolvedActivities())
                .extracting(Activity::getId)
                .containsExactly(1003L, 1002L, 1001L);
    }

    // ------------------------------------------------------------------
    // 全互斥
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A(priority=30) EXCLUSIVE B(priority=20)，A 优先 → 仅保留 A")
    void handle_twoActivitiesExclusive_highPriorityWins() {
        Activity a = activity(1003L, PromotionType.FULL_REDUCTION, 30);
        Activity b = activity(1005L, PromotionType.DISCOUNT,       20);

        when(activityConflictMapper.selectConflictsByActivityIds(anyList()))
                .thenReturn(List.of(conflict(1003L, 1005L, ConflictRelation.EXCLUSIVE)));

        PromotionContext ctx = contextWith(List.of(a, b)); // a 优先级更高
        handler.handle(ctx);

        assertThat(ctx.getResolvedActivities())
                .extracting(Activity::getId)
                .containsExactly(1003L);
    }

    @Test
    @DisplayName("三活动两两互斥（A exc B, A exc C）→ 仅保留最高优先级 A")
    void handle_allMutuallyExclusive_onlyTopPriorityKept() {
        Activity a = activity(1001L, PromotionType.FULL_REDUCTION, 30);
        Activity b = activity(1002L, PromotionType.DISCOUNT,       20);
        Activity c = activity(1003L, PromotionType.MEMBER_PRICE,   10);

        when(activityConflictMapper.selectConflictsByActivityIds(anyList()))
                .thenReturn(List.of(
                        conflict(1001L, 1002L, ConflictRelation.EXCLUSIVE),
                        conflict(1001L, 1003L, ConflictRelation.EXCLUSIVE)
                ));

        PromotionContext ctx = contextWith(List.of(a, b, c));
        handler.handle(ctx);

        assertThat(ctx.getResolvedActivities())
                .extracting(Activity::getId)
                .containsExactly(1001L);
    }

    // ------------------------------------------------------------------
    // 部分互斥
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A exc B，A 与 C 兼容 → 保留 A 和 C，丢弃 B")
    void handle_partialExclusive_conflictingActivityDropped() {
        Activity a = activity(1001L, PromotionType.FULL_REDUCTION, 30);
        Activity b = activity(1002L, PromotionType.DISCOUNT,       20);
        Activity c = activity(1003L, PromotionType.MEMBER_PRICE,   10);

        // A 与 B 互斥，A 与 C 无冲突
        when(activityConflictMapper.selectConflictsByActivityIds(anyList()))
                .thenReturn(List.of(
                        conflict(1001L, 1002L, ConflictRelation.EXCLUSIVE)
                ));

        PromotionContext ctx = contextWith(List.of(a, b, c));
        handler.handle(ctx);

        assertThat(ctx.getResolvedActivities())
                .extracting(Activity::getId)
                .containsExactlyInAnyOrder(1001L, 1003L);
        assertThat(ctx.getResolvedActivities())
                .extracting(Activity::getId)
                .doesNotContain(1002L);
    }

    @Test
    @DisplayName("COMPATIBLE 关系记录不触发丢弃 → 所有活动保留")
    void handle_compatibleRelation_allActivitiesKept() {
        Activity a = activity(1001L, PromotionType.FULL_REDUCTION, 30);
        Activity b = activity(1002L, PromotionType.DISCOUNT,       20);

        when(activityConflictMapper.selectConflictsByActivityIds(anyList()))
                .thenReturn(List.of(conflict(1001L, 1002L, ConflictRelation.COMPATIBLE)));

        PromotionContext ctx = contextWith(List.of(a, b));
        handler.handle(ctx);

        assertThat(ctx.getResolvedActivities())
                .extracting(Activity::getId)
                .containsExactlyInAnyOrder(1001L, 1002L);
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private Activity activity(long id, PromotionType type, int priority) {
        return Activity.builder()
                .id(id).name("活动-" + id).type(type)
                .status(ActivityStatus.ACTIVE).priority(priority)
                .build();
    }

    private ActivityConflict conflict(long idA, long idB, ConflictRelation relation) {
        return ActivityConflict.builder()
                .id(100L).activityIdA(idA).activityIdB(idB).relation(relation)
                .build();
    }

    private PromotionContext contextWith(List<Activity> matched) {
        return PromotionContext.builder().matchedActivities(matched).build();
    }
}
