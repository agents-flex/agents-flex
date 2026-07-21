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
package com.agentsflex.observability.jdbc;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

final class JdbcSpanExporter implements SpanExporter {
    private static final Logger logger = LoggerFactory.getLogger(JdbcSpanExporter.class);

    private final JdbcTelemetryRepository repository;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    JdbcSpanExporter(JdbcTelemetryRepository repository) {
        this.repository = repository;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        if (shutdown.get()) {
            return CompletableResultCode.ofFailure();
        }
        if (spans == null || spans.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }
        try {
            repository.writeSpans(spans);
            return CompletableResultCode.ofSuccess();
        } catch (Throwable error) {
            logger.warn("Failed to persist {} OpenTelemetry spans", spans.size(), error);
            return CompletableResultCode.ofExceptionalFailure(error);
        }
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        shutdown.set(true);
        return CompletableResultCode.ofSuccess();
    }
}
