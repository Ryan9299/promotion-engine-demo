package com.ryan.promotion.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 缓存失效事件监听器，在事务提交后执行缓存清除。
 *
 * <p>通过 {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 保证：
 * <ul>
 *   <li>事务成功提交 → 清除缓存，下次读取获取最新数据。</li>
 *   <li>事务回滚 → 不触发缓存清除，避免无谓的缓存穿透。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheInvalidateListener {

    private final PromotionCacheManager cacheManager;

    /**
     * 事务提交后处理缓存失效事件。
     * storeId 为 null 时执行全量失效，否则失效指定门店。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCacheInvalidate(CacheInvalidateEvent event) {
        if (event.getStoreId() == null) {
            cacheManager.invalidateAll();
        } else {
            cacheManager.invalidate(event.getStoreId());
        }
    }
}
