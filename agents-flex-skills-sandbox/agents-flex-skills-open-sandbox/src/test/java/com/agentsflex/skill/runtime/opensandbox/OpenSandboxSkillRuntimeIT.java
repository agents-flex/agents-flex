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
package com.agentsflex.skill.runtime.opensandbox;

import com.agentsflex.skill.runtime.SkillExecutionRequest;
import com.agentsflex.skill.runtime.SkillExecutionResult;
import com.agentsflex.skill.runtime.SkillRuntimeException;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

public class OpenSandboxSkillRuntimeIT {

    private static final String DOMAIN_PROPERTY = "agentsflex.it.opensandbox.domain";
    private static final String API_KEY_PROPERTY = "agentsflex.it.opensandbox.apiKey";

    @Test
    public void isolatesAndResumesConversationWorkspacesOnRealServer() {
        String domain = System.getProperty(DOMAIN_PROPERTY);
        assumeTrue("Set -D" + DOMAIN_PROPERTY + " to run against a real OpenSandbox server",
            domain != null && !domain.trim().isEmpty());

        String suffix = Long.toHexString(System.nanoTime());
        String firstConversation = "it-open-a-" + suffix;
        String secondConversation = "it-open-b-" + suffix;
        OpenSandboxSkillRuntime first = runtime(domain, firstConversation);
        OpenSandboxSkillRuntime resumed = null;
        OpenSandboxSkillRuntime isolated = null;
        try {
            first.getFileSystem().writeText("marker.txt", "alpha");
            assertEquals("alpha", first.getFileSystem().readText("marker.txt", 100));
            first.close();

            resumed = runtime(domain, firstConversation);
            assertEquals("alpha", resumed.getFileSystem().readText("marker.txt", 100));

            isolated = runtime(domain, secondConversation);
            isolated.getFileSystem().writeText("marker.txt", "beta");
            assertEquals("beta", isolated.getFileSystem().readText("marker.txt", 100));
            assertEquals("alpha", resumed.getFileSystem().readText("marker.txt", 100));
            assertCrossConversationPathRejected(resumed, secondConversation);
        } finally {
            destroy(isolated);
            if (resumed != null) {
                destroy(resumed);
            } else {
                destroy(first);
            }
        }
    }

    @Test
    public void isolatesConcurrentConversationsOnRealServer() throws Exception {
        String domain = System.getProperty(DOMAIN_PROPERTY);
        assumeTrue("Set -D" + DOMAIN_PROPERTY + " to run against a real OpenSandbox server",
            domain != null && !domain.trim().isEmpty());

        int conversationCount = 4;
        String suffix = Long.toHexString(System.nanoTime());
        List<OpenSandboxSkillRuntime> runtimes = new ArrayList<>(conversationCount);
        List<String> expectedValues = new ArrayList<>(conversationCount);
        ExecutorService executor = Executors.newFixedThreadPool(conversationCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> futures = new ArrayList<>(conversationCount);
            for (int i = 0; i < conversationCount; i++) {
                final String conversationId = "it-open-concurrent-" + i + "-" + suffix;
                final OpenSandboxSkillRuntime runtime = runtime(domain, conversationId);
                final String expected = "open-value-" + i;
                runtimes.add(runtime);
                expectedValues.add(expected + "-2");
                futures.add(executor.submit(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        start.await();
                        for (int round = 0; round < 3; round++) {
                            String value = expected + "-" + round;
                            SkillExecutionResult result = runtime.execute(new SkillExecutionRequest(
                                "printf '%s' '" + value + "' > marker.txt", null, 30000,
                                Collections.<String, String>emptyMap()));
                            assertEquals(0, result.getExitCode());
                            assertEquals(value, runtime.getFileSystem().readText("marker.txt", 100));
                        }
                        return null;
                    }
                }));
            }

            start.countDown();
            for (Future<Void> future : futures) {
                future.get(3, TimeUnit.MINUTES);
            }
            for (int i = 0; i < conversationCount; i++) {
                assertEquals(expectedValues.get(i),
                    runtimes.get(i).getFileSystem().readText("marker.txt", 100));
            }
        } finally {
            executor.shutdownNow();
            for (OpenSandboxSkillRuntime runtime : runtimes) {
                destroy(runtime);
            }
        }
    }

    private static OpenSandboxSkillRuntime runtime(String domain, String conversationId) {
        ConnectionConfig.Builder connection = ConnectionConfig.builder().domain(domain);
        String apiKey = System.getProperty(API_KEY_PROPERTY);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            connection.apiKey(apiKey);
        }
        return OpenSandboxSkillRuntime.builder()
            .connectionConfig(connection.build())
            .conversationId(conversationId)
            .build();
    }

    private static void assertCrossConversationPathRejected(OpenSandboxSkillRuntime runtime,
                                                             String otherConversation) {
        try {
            runtime.getFileSystem().readText(
                "/workspace/conversations/" + otherConversation + "/marker.txt", 100);
            fail("Expected cross-conversation path to be rejected");
        } catch (SkillRuntimeException expected) {
            assertTrue(expected.getMessage().contains("outside the conversation workspace"));
        }
    }

    private static void destroy(OpenSandboxSkillRuntime runtime) {
        if (runtime == null) {
            return;
        }
        try {
            runtime.destroyConversationSandbox();
        } finally {
            runtime.close();
        }
    }
}
