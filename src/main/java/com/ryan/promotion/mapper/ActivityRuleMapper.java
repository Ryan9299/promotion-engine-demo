package com.ryan.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ryan.promotion.model.entity.ActivityRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 活动规则 Mapper，提供规则表的基础 CRUD 及批量查询能力。
 */
@Mapper
public interface ActivityRuleMapper extends BaseMapper<ActivityRule> {

    /**
     * 批量查询指定活动 ID 列表对应的规则。
     * 供 PromotionCacheManager 预加载门店活动规则时使用。
     *
     * @param activityIds 活动 ID 列表
     * @return 活动规则列表
     */
    @Select("<script>" +
            "SELECT * FROM t_promo_activity_rule " +
            "WHERE deleted = 0 " +
            "  AND activity_id IN " +
            "<foreach collection='activityIds' item='id' open='(' separator=',' close=')'>" +
            "  #{id}" +
            "</foreach>" +
            "</script>")
    List<ActivityRule> selectByActivityIds(@Param("activityIds") List<Long> activityIds);
}
