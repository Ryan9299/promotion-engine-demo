package com.ryan.promotion.chain;

import com.ryan.promotion.handler.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 促销责任链构建器，负责将四个 Handler 按固定顺序组装成链。
 *
 * <p>链路固定为：
 * <pre>
 *   ActivityFilterHandler
 *       → ConflictResolveHandler
 *           → CalcHandler
 *               → ResultAssembleHandler（链尾）
 * </pre>
 *
 * <p>所有 Handler 均为 Spring 单例，无状态（可变状态通过 {@link PromotionContext} 传递），
 * 因此 next 指针在 {@link PostConstruct} 阶段设置一次即可，并发安全。
 *
 * <p>{@link com.ryan.promotion.service.PromotionService} 通过注入本 Builder 获取链头，
 * 调用 {@link #getHead()} 后执行 {@code head.handle(context)} 即可驱动整条链。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromotionChainBuilder {

    private final ActivityFilterHandler filterHandler;
    private final ConflictResolveHandler conflictResolveHandler;
    private final CalcHandler calcHandler;
    private final ResultAssembleHandler resultAssembleHandler;

    /** 链头节点，应用启动时初始化，运行期只读 */
    private PromotionHandler head;

    /**
     * 在 Spring 完成所有 Bean 注入后，按顺序链接各 Handler 节点。
     */
    @PostConstruct
    public void buildChain() {
        filterHandler.setNext(conflictResolveHandler);
        conflictResolveHandler.setNext(calcHandler);
        calcHandler.setNext(resultAssembleHandler);
        // resultAssembleHandler 为链尾，next 保持 null

        head = filterHandler;
        log.info("促销责任链组装完成：ActivityFilter → ConflictResolve → Calc → ResultAssemble");
    }

    /**
     * 返回责任链头节点。
     * 调用方通过 {@code getHead().handle(context)} 驱动整条链完成促销计算。
     *
     * @return 链头 Handler（ActivityFilterHandler）
     */
    public PromotionHandler getHead() {
        return head;
    }
}
