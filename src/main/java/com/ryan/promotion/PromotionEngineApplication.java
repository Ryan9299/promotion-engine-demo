package com.ryan.promotion;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 营销活动叠加引擎启动类。
 * 支持满减、折扣、赠品、会员等级价四种活动类型的任意组合计算，
 * 面向连锁零售场景，集成两级缓存（Caffeine + Redis）与灰度发布能力。
 */
@SpringBootApplication
@MapperScan("com.ryan.promotion.mapper")
public class PromotionEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(PromotionEngineApplication.class, args);
    }
}
