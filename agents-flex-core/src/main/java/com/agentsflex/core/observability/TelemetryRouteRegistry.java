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

/** Application-owned registry used to resolve a persisted route id at execution time. */
public final class TelemetryRouteRegistry implements AutoCloseable {
    private final Map<String, TelemetryRoute> routes = new ConcurrentHashMap<>();
    private boolean closed;

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

    public TelemetryRoute get(String routeId) {
        return routes.get(routeId);
    }

    public TelemetryRoute require(String routeId) {
        TelemetryRoute route = get(routeId);
        if (route == null) {
            throw new IllegalArgumentException("Unknown telemetry route: " + routeId);
        }
        return route;
    }

    public Collection<TelemetryRoute> getRoutes() {
        return Collections.unmodifiableList(new ArrayList<>(routes.values()));
    }

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
