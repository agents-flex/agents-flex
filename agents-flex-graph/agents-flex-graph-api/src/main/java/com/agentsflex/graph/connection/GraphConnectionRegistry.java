package com.agentsflex.graph.connection;

import com.agentsflex.graph.identifier.GraphIdentifiers;
import com.agentsflex.graph.GraphStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按名称管理多个图数据库连接。
 *
 * <p>默认情况下注册表拥有已注册 {@link GraphStore} 的生命周期；移除或关闭注册表时会关闭对应存储。
 * 通过 {@link #GraphConnectionRegistry(boolean)} 关闭生命周期托管后，可交给 Spring 等外部容器负责关闭。
 * 适合支撑管理后台中的多环境、多后端连接选择。</p>
 */
public final class GraphConnectionRegistry implements AutoCloseable {
    private final Map<String, GraphStore> stores = new ConcurrentHashMap<>();
    /**
     * 是否由注册表负责关闭已注册的存储。
     */
    private final boolean closeStores;
    private volatile boolean closed;

    /**
     * 创建一个由注册表拥有连接生命周期的注册表。
     *
     * <p>适用于直接使用 Graph API 的应用；调用 {@link #remove(String)} 或
     * {@link #close()} 时会关闭对应的 {@link GraphStore}。</p>
     */
    public GraphConnectionRegistry() {
        this(true);
    }

    /**
     * 创建连接注册表。
     *
     * @param closeStores 是否在移除或关闭注册表时关闭存储；Spring 容器托管 Bean 时应传入 {@code false}
     */
    public GraphConnectionRegistry(boolean closeStores) {
        this.closeStores = closeStores;
    }

    /**
     * 注册新连接；同名连接已存在时拒绝覆盖。
     */
    public synchronized void register(String name, GraphStore store) {
        String key = GraphIdentifiers.requireText(name, "connection name");
        if (store == null) throw new IllegalArgumentException("graph store must not be null");
        if (closed) throw new IllegalStateException("connection registry is closed");
        if (stores.putIfAbsent(key, store) != null) {
            throw new IllegalArgumentException("graph connection already exists: " + key);
        }
    }

    /**
     * @return 指定名称的连接；不存在时返回 {@code null}
     */
    public GraphStore get(String name) {
        return name == null ? null : stores.get(name);
    }

    /**
     * 获取连接，不存在时抛出包含连接名的异常。
     */
    public GraphStore require(String name) {
        GraphStore store = get(name);
        if (store == null) throw new IllegalArgumentException("unknown graph connection: " + name);
        return store;
    }

    /**
     * @return 按名称排序的连接列表
     */
    public List<String> names() {
        List<String> result = new ArrayList<>(stores.keySet());
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }

    /**
     * 对全部连接执行健康检查，单个连接失败不会中断其余连接。
     */
    public Map<String, GraphHealth> health() {
        Map<String, GraphHealth> result = new LinkedHashMap<>();
        for (String name : names()) {
            try {
                result.put(name, stores.get(name).health());
            } catch (RuntimeException e) {
                result.put(name, GraphHealth.down(name, e.getMessage(), -1L));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 移除连接；是否关闭底层存储取决于构造时的生命周期托管策略。
     */
    public synchronized boolean remove(String name) {
        GraphStore store = name == null ? null : stores.remove(name);
        if (store == null) return false;
        if (closeStores) store.close();
        return true;
    }

    /**
     * 关闭并清空全部连接。
     */
    @Override
    public synchronized void close() {
        closed = true;
        RuntimeException failure = null;
        if (!closeStores) {
            stores.clear();
            return;
        }
        for (GraphStore store : stores.values()) {
            try {
                store.close();
            } catch (RuntimeException e) {
                if (failure == null) failure = e;
            }
        }
        stores.clear();
        if (failure != null) throw failure;
    }
}
