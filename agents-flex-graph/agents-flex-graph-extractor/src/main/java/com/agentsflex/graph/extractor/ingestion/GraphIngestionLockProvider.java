package com.agentsflex.graph.extractor.ingestion;

/**
 * 为“Space + documentId”提供互斥执行边界的扩展点。
 *
 * <p>单进程可以使用 {@link LocalGraphIngestionLockProvider}。多实例生产环境应接入数据库租约、Redis
 * 或任务系统实现，并处理获取超时、租约续期和持有者身份校验。锁只缩小并发窗口，状态存储的 revision
 * CAS 和图写入 operationId 幂等仍然必须保留。</p>
 */
public interface GraphIngestionLockProvider {
    /**
     * 获取指定文档的排他锁；获取失败时实现应抛出运行时异常。
     *
     * @return 必须由获取线程关闭的锁租约，不能为空
     */
    Lease acquire(String space, String documentId);

    /**
     * 一次锁持有期。覆盖 close 声明以避免业务代码处理无意义的受检异常。
     */
    interface Lease extends AutoCloseable {
        /**
         * 释放当前锁；实现应保证重复关闭不会重复解锁。
         */
        @Override
        void close();
    }
}
