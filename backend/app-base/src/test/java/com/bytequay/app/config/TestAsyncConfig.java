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
package com.bytequay.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static com.bytequay.app.config.AsyncConfig.IO_EXECUTOR;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAsyncConfig
{
    @Test
    void closingTheContextInterruptsBackgroundWork()
            throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var context = new AnnotationConfigApplicationContext(AsyncConfig.class)) {
            ExecutorService executor = context.getBean(IO_EXECUTOR, ExecutorService.class);
            try {
                executor.execute(() -> {
                    entered.countDown();
                    try {
                        release.await(10, SECONDS);
                    }
                    catch (InterruptedException failure) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                });
                assertThat(entered.await(5, SECONDS)).isTrue();
                context.close();
                assertThat(interrupted.await(5, SECONDS)).isTrue();
                assertThat(executor.awaitTermination(5, SECONDS)).isTrue();
                assertThatThrownBy(() -> executor.execute(() -> {})).isInstanceOf(RejectedExecutionException.class);
            }
            finally {
                release.countDown();
            }
        }
    }
}
