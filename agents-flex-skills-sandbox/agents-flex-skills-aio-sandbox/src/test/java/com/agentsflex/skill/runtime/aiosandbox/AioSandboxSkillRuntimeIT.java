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
package com.agentsflex.skill.runtime.aiosandbox;

import com.agentsflex.skill.runtime.SkillExecutionRequest;
import com.agentsflex.skill.runtime.SkillExecutionResult;
import com.agentsflex.skill.runtime.SkillRuntimeException;
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

public class AioSandboxSkillRuntimeIT {

    private static final String BASE_URL_PROPERTY = "agentsflex.it.aioSandbox.baseUrl";
    private static final String TOKEN_PROPERTY = "agentsflex.it.aioSandbox.token";

    @Test
    public void isolatesAndResumesConversationWorkspacesOnRealServer() {
        String baseUrl = System.getProperty(BASE_URL_PROPERTY);
        assumeTrue("Set -D" + BASE_URL_PROPERTY + " to run against a real AIO Sandbox server",
            baseUrl != null && !baseUrl.trim().isEmpty());

        String suffix = Long.toHexString(System.nanoTime());
        String firstConversation = "it-aio-a-" + suffix;
        String secondConversation = "it-aio-b-" + suffix;
        AioSandboxSkillRuntime first = runtime(baseUrl, firstConversation);
        AioSandboxSkillRuntime resumed = null;
        AioSandboxSkillRuntime isolated = null;
        try {
            first.getFileSystem().writeText("marker.txt", "alpha");
            assertEquals("alpha", first.getFileSystem().readText("marker.txt", 100));
            first.close();

            resumed = runtime(baseUrl, firstConversation);
            assertEquals("alpha", resumed.getFileSystem().readText("marker.txt", 100));

            isolated = runtime(baseUrl, secondConversation);
            isolated.getFileSystem().writeText("marker.txt", "beta");
            assertEquals("beta", isolated.getFileSystem().readText("marker.txt", 100));
            assertEquals("alpha", resumed.getFileSystem().readText("marker.txt", 100));
            assertCrossConversationPathRejected(resumed, secondConversation);
        } finally {
            close(isolated);
            close(resumed);
            close(first);
        }
    }

    @Test
    public void isolatesConcurrentConversationsOnRealServer() throws Exception {
        String baseUrl = System.getProperty(BASE_URL_PROPERTY);
        assumeTrue("Set -D" + BASE_URL_PROPERTY + " to run against a real AIO Sandbox server",
            baseUrl != null && !baseUrl.trim().isEmpty());

        int conversationCount = 8;
        String suffix = Long.toHexString(System.nanoTime());
        List<AioSandboxSkillRuntime> runtimes = new ArrayList<>(conversationCount);
        List<String> expectedValues = new ArrayList<>(conversationCount);
        ExecutorService executor = Executors.newFixedThreadPool(conversationCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> futures = new ArrayList<>(conversationCount);
            for (int i = 0; i < conversationCount; i++) {
                final String conversationId = "it-aio-concurrent-" + i + "-" + suffix;
                final AioSandboxSkillRuntime runtime = runtime(baseUrl, conversationId);
                final String expected = "aio-value-" + i;
                runtimes.add(runtime);
                expectedValues.add(expected + "-4");
                futures.add(executor.submit(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        start.await();
                        for (int round = 0; round < 5; round++) {
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
                future.get(2, TimeUnit.MINUTES);
            }
            for (int i = 0; i < conversationCount; i++) {
                assertEquals(expectedValues.get(i),
                    runtimes.get(i).getFileSystem().readText("marker.txt", 100));
            }
        } finally {
            executor.shutdownNow();
            for (AioSandboxSkillRuntime runtime : runtimes) {
                close(runtime);
            }
        }
    }

    private static AioSandboxSkillRuntime runtime(String baseUrl, String conversationId) {
        AioSandboxSkillRuntime.Builder builder = AioSandboxSkillRuntime.builder()
            .baseUrl(baseUrl)
            .conversationId(conversationId);
        String token = System.getProperty(TOKEN_PROPERTY);
        if (token != null && !token.trim().isEmpty()) {
            builder.bearerToken(token);
        }
        return builder.build();
    }

    private static void assertCrossConversationPathRejected(AioSandboxSkillRuntime runtime,
                                                             String otherConversation) {
        try {
            runtime.getFileSystem().readText(
                "/home/gem/workspace/conversations/" + otherConversation + "/marker.txt", 100);
            fail("Expected cross-conversation path to be rejected");
        } catch (SkillRuntimeException expected) {
            assertTrue(expected.getMessage().contains("outside the conversation workspace"));
        }
    }

    private static void close(AioSandboxSkillRuntime runtime) {
        if (runtime != null) {
            runtime.close();
        }
    }
}
