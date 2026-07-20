/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.app.web;

import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.service.ai.AiReviewService;
import com.bytequay.app.service.ai.LlmReviewerRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TestAiReviewController
{
    @Test
    void concurrentQuickReviewStartsEnqueueOnlyOneRun()
            throws Exception
    {
        AtomicInteger enqueued = new AtomicInteger();
        AiReviewController controller = new AiReviewController(
                mock(AiReviewService.class),
                mock(LlmReviewerRegistry.class),
                mock(AppSettingsStore.class),
                ignored -> enqueued.incrementAndGet());
        int callerCount = 16;
        CountDownLatch ready = new CountDownLatch(callerCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(callerCount);
        try {
            List<Future<Map<String, String>>> responses = new ArrayList<>();
            for (int i = 0; i < callerCount; i++) {
                responses.add(callers.submit(() -> {
                    ready.countDown();
                    start.await();
                    return controller.startQuickReview("pr-1");
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Map<String, String>> response : responses) {
                assertThat(response.get(5, TimeUnit.SECONDS)).containsEntry("state", "RUNNING");
            }
            assertThat(enqueued).hasValue(1);
        }
        finally {
            callers.shutdownNow();
        }
    }

    @Test
    void rejectedQuickReviewSubmissionDoesNotRemainRunning()
    {
        AiReviewController controller = new AiReviewController(
                mock(AiReviewService.class),
                mock(LlmReviewerRegistry.class),
                mock(AppSettingsStore.class),
                ignored -> {
                    throw new RejectedExecutionException("executor full");
                });

        assertThatThrownBy(() -> controller.startQuickReview("pr-1"))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(controller.quickReviewStatus("pr-1"))
                .extracting(AiReviewController.StatusResponse::state,
                        AiReviewController.StatusResponse::error)
                .containsExactly("FAILED", "executor full");
    }
}
