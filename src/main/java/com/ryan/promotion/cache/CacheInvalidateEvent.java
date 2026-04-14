package com.ryan.promotion.cache;

import org.springframework.context.ApplicationEvent;

/**
 * 缓存失效事件，在事务方法中发布，由 {@link CacheInvalidateListener} 在事务提交后处理。
 *
 * <p>解决"事务内失效缓存 → 事务回滚 → 缓存已被清除"导致的不一致问题。
 * 配合 {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 使用，
 * 确保仅在 DB 变更真正落地后才清除缓存。
 *
 * @see CacheInvalidateListener
 */
public class CacheInvalidateEvent extends ApplicationEvent {

    /** 需要失效的门店 ID，为 null 时表示全量失效 */
    private final Long storeId;

    public CacheInvalidateEvent(Object source, Long storeId) {
        super(source);
        this.storeId = storeId;
    }

    public Long getStoreId() {
        return storeId;
    }

    /** 创建全量失效事件 */
    public static CacheInvalidateEvent all(Object source) {
        return new CacheInvalidateEvent(source, null);
    }

    /** 创建指定门店失效事件 */
    public static CacheInvalidateEvent of(Object source, Long storeId) {
        return new CacheInvalidateEvent(source, storeId);
    }
}
