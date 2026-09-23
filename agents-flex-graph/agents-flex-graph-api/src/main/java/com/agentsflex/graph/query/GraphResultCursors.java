package com.agentsflex.graph.query;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * SDK 内部用于把物化结果适配到统一游标协议的工具类。
 *
 * <p>该包装器不产生新的后端流，只是让不支持原生流式读取的适配器也能提供统一的
 * {@link GraphResultCursor} 生命周期和迭代接口。</p>
 */
public final class GraphResultCursors {
    /**
     * 工具类不允许实例化。
     */
    private GraphResultCursors() {
    }

    /**
     * 将物化结果包装为可关闭游标。
     *
     * @param result 待包装的物化结果，不允许为 {@code null}
     * @return 只读、可关闭的结果游标
     */
    public static GraphResultCursor of(final GraphResult result) {
        final List<GraphRecord> records = result.getRecords();
        return new GraphResultCursor() {
            private final Iterator<GraphRecord> iterator = records.iterator();
            private boolean closed;

            @Override
            public boolean hasNext() {
                return !closed && iterator.hasNext();
            }

            @Override
            public GraphRecord next() {
                if (closed) throw new NoSuchElementException("graph result cursor is closed");
                return iterator.next();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("cursor is read-only");
            }

            @Override
            public GraphResultMetadata getMetadata() {
                return result.getMetadata();
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }
}
