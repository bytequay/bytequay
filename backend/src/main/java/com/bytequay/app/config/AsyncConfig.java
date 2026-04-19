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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AsyncConfig
{
    public static final String APPLICATION_EXECUTOR = "applicationExecutor";
    public static final String IO_EXECUTOR = "ioExecutor";

    private static final int APPLICATION_EXECUTOR_CORE_POOL_SIZE = 4;
    private static final int APPLICATION_EXECUTOR_MAX_POOL_SIZE = 16;
    private static final int APPLICATION_EXECUTOR_QUEUE_CAPACITY = 100;
    private static final int APPLICATION_EXECUTOR_AWAIT_TERMINATION_SECONDS = 10;

    /**
     * For top-level orchestration tasks (sync job, controller hand-offs).
     * Bounded so we don't fork unlimited work; 4 cores is plenty since
     * each task handed to this executor is short-lived itself — the heavy
     * IO inside fans out onto the {@link #IO_EXECUTOR}.
     */
    @Bean(name = APPLICATION_EXECUTOR)
    public Executor applicationExecutor()
    {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("bytequay-");
        executor.setCorePoolSize(APPLICATION_EXECUTOR_CORE_POOL_SIZE);
        executor.setMaxPoolSize(APPLICATION_EXECUTOR_MAX_POOL_SIZE);
        executor.setQueueCapacity(APPLICATION_EXECUTOR_QUEUE_CAPACITY);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(APPLICATION_EXECUTOR_AWAIT_TERMINATION_SECONDS);
        executor.initialize();
        return executor;
    }

    /**
     * For IO-bound GitHub fan-outs (per-PR detail sub-fetches: timeline,
     * reviews, files, comments, …). Backed by virtual threads so a
     * "parent" task on {@link #APPLICATION_EXECUTOR} that submits 6
     * children and {@code .join()}s them can never deadlock against
     * its own children — virtual threads are unlimited and parking
     * doesn't pin a carrier.
     *
     * <p>This separation fixes the executor-starvation deadlock observed
     * before this change: parent tasks filled all 4 applicationExecutor
     * threads, their own children couldn't acquire a thread, and every
     * {@code fetchDetailFromGitHub} request hung at {@code .join()}.
     */
    @Bean(name = IO_EXECUTOR, destroyMethod = "close")
    public ExecutorService ioExecutor()
    {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
