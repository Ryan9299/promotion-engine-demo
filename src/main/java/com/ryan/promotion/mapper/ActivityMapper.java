package com.ryan.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ryan.promotion.model.entity.Activity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 营销活动 Mapper，提供活动主表的基础 CRUD 及常用查询。
 */
@Mapper
public interface ActivityMapper extends BaseMapper<Activity> {

    /**
     * 查询指定门店在当前时间点有效的所有活动（ACTIVE 或 GRAY 状态）。
     * 结果按优先级降序排列，供责任链过滤节点使用。
     *
     * @param storeId 门店 ID（灰度规则在应用层判断，此处不过滤）
     * @param now     当前时间，用于比对 start_time / end_time
     * @return 有效活动列表
     */
    @Select("SELECT * FROM t_promo_activity " +
            "WHERE deleted = 0 " +
            "  AND status IN ('ACTIVE', 'GRAY') " +
            "  AND start_time <= #{now} " +
            "  AND end_time   >= #{now} " +
            "ORDER BY priority DESC")
    List<Activity> selectActiveActivities(@Param("storeId") Long storeId,
                                          @Param("now") LocalDateTime now);
}
