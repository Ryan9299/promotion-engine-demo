package com.ryan.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ryan.promotion.model.entity.ActivityConflict;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 活动冲突关系 Mapper，提供互斥/兼容关系的查询能力。
 */
@Mapper
public interface ActivityConflictMapper extends BaseMapper<ActivityConflict> {

    /**
     * 查询给定活动集合内所有两两之间的冲突关系。
     * ConflictResolveHandler 使用此结果构建冲突图。
     *
     * @param activityIds 活动 ID 列表
     * @return 涉及列表内活动的所有冲突记录
     */
    @Select("<script>" +
            "SELECT * FROM t_promo_activity_conflict " +
            "WHERE deleted = 0 " +
            "  AND activity_id_a IN " +
            "<foreach collection='activityIds' item='id' open='(' separator=',' close=')'>" +
            "  #{id}" +
            "</foreach>" +
            "  AND activity_id_b IN " +
            "<foreach collection='activityIds' item='id' open='(' separator=',' close=')'>" +
            "  #{id}" +
            "</foreach>" +
            "</script>")
    List<ActivityConflict> selectConflictsByActivityIds(@Param("activityIds") List<Long> activityIds);
}
