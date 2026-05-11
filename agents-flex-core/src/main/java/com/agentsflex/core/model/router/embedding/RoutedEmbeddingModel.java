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
package com.agentsflex.core.model.router.embedding;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.embedding.EmbeddingModel;
import com.agentsflex.core.model.embedding.EmbeddingOptions;
import com.agentsflex.core.model.router.balance.ModelLoadBalancer;
import com.agentsflex.core.model.router.breaker.CircuitBreaker;
import com.agentsflex.core.model.router.core.AbstractModelRouter;
import com.agentsflex.core.model.router.endpoint.ModelEndpoint;
import com.agentsflex.core.model.router.retry.RetryPolicy;
import com.agentsflex.core.store.VectorData;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public class RoutedEmbeddingModel  extends AbstractModelRouter<EmbeddingModel> implements EmbeddingModel {

    public RoutedEmbeddingModel(
        List<ModelEndpoint<EmbeddingModel>> endpoints,
        ModelLoadBalancer<EmbeddingModel> loadBalancer,
        RetryPolicy retryPolicy,
        CircuitBreaker<EmbeddingModel> circuitBreaker) {

        super(
            endpoints,
            loadBalancer,
            retryPolicy,
            circuitBreaker
        );
    }

    @Override
    public VectorData embed(Document document, EmbeddingOptions options) {
        return execute(
            model -> model.embed(document, options),
            extractTags(options)
        );
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractTags(EmbeddingOptions options) {

        Object value = options.getMetadata("modelTags");

        if (value instanceof Set<?>) {
            return (Set<String>) value;
        }

        return Collections.emptySet();
    }
}
