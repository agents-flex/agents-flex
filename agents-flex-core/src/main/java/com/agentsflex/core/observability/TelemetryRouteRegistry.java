/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.core.observability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 由宿主应用持有的遥测路由注册表，用于在执行入口把持久化的 route ID 解析成 {@link TelemetryRoute}。
 *
 * <p>注册表只管理可观测路由，不感知智能体、模型或其他业务实体。业务系统可以自行决定在哪个对象上保存
 * route ID。注册表拥有已成功注册 Route 的关闭责任；注册失败的 Route 仍由调用方处理。</p>
 */
public final class TelemetryRouteRegistry implements AutoCloseable {
    /**
     * route ID 到长生命周期 Route 的并发映射。读取位于请求路径，因此使用 ConcurrentHashMap 避免全局锁。
     */
    private final Map<String, TelemetryRoute> routes = new ConcurrentHashMap<>();

    /** 注册表是否已关闭；该字段只在 synchronized 的 register/close 临界区内读写。 */
    private boolean closed;

    /**
     * 注册路由。同一个 ID 只能注册一次，注册表关闭后不再接受新路由。
     */
    public synchronized TelemetryRouteRegistry register(TelemetryRoute route) {
        if (route == null) {
            throw new IllegalArgumentException("route must not be null");
        }
        if (closed) {
            throw new IllegalStateException("Telemetry route registry is closed");
        }
        TelemetryRoute previous = routes.putIfAbsent(route.getId(), route);
        if (previous != null) {
            throw new IllegalStateException("Telemetry route already registered: " + route.getId());
        }
        return this;
    }

    /**
     * 查询路由；不存在时返回 {@code null}，适合允许回退到全局 Observability 的场景。
     */
    public TelemetryRoute get(String routeId) {
        return routes.get(routeId);
    }

    /**
     * 查询必需路由；不存在时立即抛出异常，适合阻止错误配置进入实际业务执行。
     */
    public TelemetryRoute require(String routeId) {
        TelemetryRoute route = get(routeId);
        if (route == null) {
            throw new IllegalArgumentException("Unknown telemetry route: " + routeId);
        }
        return route;
    }

    /** 返回当前路由的只读快照，避免调用方修改注册表内部集合。 */
    public Collection<TelemetryRoute> getRoutes() {
        return Collections.unmodifiableList(new ArrayList<>(routes.values()));
    }

    /**
     * 依次关闭所有已注册 Route 并清空注册表。该操作幂等，并与 register 串行化以避免关闭期间新增泄漏。
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (TelemetryRoute route : routes.values()) {
            route.close();
        }
        routes.clear();
    }
}
