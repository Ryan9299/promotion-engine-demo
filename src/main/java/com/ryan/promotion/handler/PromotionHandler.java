package com.ryan.promotion.handler;

/**
 * 促销责任链抽象基类。
 *
 * <p>每个具体 Handler 实现 {@link #handle(PromotionContext)} 执行本节点逻辑，
 * 然后调用 {@link #passToNext(PromotionContext)} 将控制权传递给下一节点。
 *
 * <p>节点顺序由 {@link com.ryan.promotion.chain.PromotionChainBuilder} 在启动时固定组装：
 * {@code ActivityFilterHandler → ConflictResolveHandler → CalcHandler → ResultAssembleHandler}。
 *
 * <p>所有可变状态通过 {@link PromotionContext} 传递，Handler 本身无状态，Spring 单例安全。
 */
public abstract class PromotionHandler {

    /** 下一个责任链节点，由 PromotionChainBuilder 在 @PostConstruct 阶段注入 */
    private PromotionHandler next;

    /**
     * 设置下一个处理节点，由 PromotionChainBuilder 调用，业务代码勿直接调用。
     *
     * @param next 下一个 Handler
     */
    public void setNext(PromotionHandler next) {
        this.next = next;
    }

    /**
     * 执行本节点的促销处理逻辑。
     * 实现类完成本节点业务后，通常应调用 {@link #passToNext(PromotionContext)} 继续传递。
     *
     * @param context 贯穿整条链的上下文对象
     */
    public abstract void handle(PromotionContext context);

    /**
     * 将上下文传递给链中下一个节点。
     * 若本节点为链尾（next 为 null），则调用无效。
     *
     * @param context 贯穿整条链的上下文对象
     */
    protected void passToNext(PromotionContext context) {
        if (next != null) {
            next.handle(context);
        }
    }
}
