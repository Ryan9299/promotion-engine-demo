package com.ryan.promotion.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.ryan.promotion.model.enums.ConflictRelation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 活动互斥/兼容关系表实体，对应 t_promo_activity_conflict。
 * 记录任意两个活动之间的叠加关系，互斥时高优先级活动保留。
 * (activityIdA, activityIdB) 联合唯一，约定 idA < idB 存储。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_promo_activity_conflict")
public class ActivityConflict {

    /** 主键，雪花算法生成 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 活动 A 的 ID（较小值） */
    private Long activityIdA;

    /** 活动 B 的 ID（较大值） */
    private Long activityIdB;

    /** 冲突关系：EXCLUSIVE=互斥 / COMPATIBLE=兼容 */
    private ConflictRelation relation;

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
