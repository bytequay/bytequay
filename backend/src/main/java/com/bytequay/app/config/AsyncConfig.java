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
    public static final String REVIEW_EXECUTOR = "reviewExecutor";
    public static final String AGENT_TURN_EXECUTOR = "agentTurnExecutor";
    public static final String CHECKPOINT_EXECUTOR = "checkpointExecutor";
    public static final String DS4_SUPERVISOR_EXECUTOR = "ds4SupervisorExecutor";
    public static final String DS4_WORK_EXECUTOR = "ds4WorkExecutor";
    public static final String PROCESS_IO_EXECUTOR = "processIoExecutor";

    private static final int APPLICATION_EXECUTOR_CORE_POOL_SIZE = 4;
    private static final int APPLICATION_EXECUTOR_MAX_POOL_SIZE = 16;
    private static final int APPLICATION_EXECUTOR_QUEUE_CAPACITY = 100;
    private static final int APPLICATION_EXECUTOR_AWAIT_TERMINATION_SECONDS = 10;

    private static final int REVIEW_EXECUTOR_CORE_POOL_SIZE = 2;
    private static final int REVIEW_EXECUTOR_MAX_POOL_SIZE = 2;
    private static final int REVIEW_EXECUTOR_QUEUE_CAPACITY = 50;
    private static final int REVIEW_EXECUTOR_AWAIT_TERMINATION_SECONDS = 5;

    private static final int CHECKPOINT_EXECUTOR_POOL_SIZE = 2;

    /**
     * For top-level orchestration threads (sync job, controller hand-offs).
     * Bounded so we don't fork unlimited work; 4 cores is plenty since
     * each thread handed to this executor is short-lived itself — the heavy
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
     * "parent" thread on {@link #APPLICATION_EXECUTOR} that submits 6
     * children and {@code .join()}s them can never deadlock against
     * its own children — virtual threads are unlimited and parking
     * doesn't pin a carrier.
     *
     * <p>This separation fixes the executor-starvation deadlock observed
     * before this change: parent threads filled all 4 applicationExecutor
     * threads, their own children couldn't acquire a thread, and every
     * {@code fetchDetailFromGitHub} request hung at {@code .join()}.
     */
    @Bean(name = IO_EXECUTOR, destroyMethod = "close")
    public ExecutorService ioExecutor()
    {
        return newVirtualThreadPerTaskExecutor("io-");
    }

    /**
     * Runs logical agent turns. CLI-backed turns block on subprocess IO and
     * API-backed turns block on model calls, so virtual threads keep the
     * scheduler boundary cheap without letting controllers own threads.
     */
    @Bean(name = AGENT_TURN_EXECUTOR, destroyMethod = "close")
    public ExecutorService agentTurnExecutor()
    {
        return newVirtualThreadPerTaskExecutor("agent-turn-");
    }

    /**
     * Small bounded pool for checkpoint summarisation. Per-thread locking
     * already serialises work for one conversation; this caps cross-thread
     * summariser fan-out.
     */
    @Bean(name = CHECKPOINT_EXECUTOR, destroyMethod = "shutdownNow")
    public ExecutorService checkpointExecutor()
    {
        return Executors.newFixedThreadPool(
                CHECKPOINT_EXECUTOR_POOL_SIZE,
                Thread.ofPlatform().name("checkpoint-scheduler-", 0).daemon(true).factory());
    }

    /**
     * Single-writer supervisor for the ds4 lifecycle state machine.
     */
    @Bean(name = DS4_SUPERVISOR_EXECUTOR, destroyMethod = "shutdownNow")
    public ExecutorService ds4SupervisorExecutor()
    {
        return Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("ds4-supervisor-", 0).daemon(true).factory());
    }

    /**
     * Long-lived ds4 helper work such as installers, process watchers, and
     * log capture.
     */
    @Bean(name = DS4_WORK_EXECUTOR, destroyMethod = "close")
    public ExecutorService ds4WorkExecutor()
    {
        return newVirtualThreadPerTaskExecutor("ds4-work-");
    }

    /**
     * Subprocess stdout/stderr drains. These tasks are intentionally separate
     * from agent turns so a chatty child process cannot consume the turn lane.
     */
    @Bean(name = PROCESS_IO_EXECUTOR, destroyMethod = "close")
    public ExecutorService processIoExecutor()
    {
        return newVirtualThreadPerTaskExecutor("process-io-");
    }

    /**
     * Runs interactive review-pass bodies (the multi-minute LLM panel
     * fan-out) off the HTTP request thread so {@code POST
     * /api/reviews/start} can return as soon as the pass is seated.
     * Bounded low on purpose: the heavy work is the model calls, and
     * the single-connection SQLite pool means we don't want many review
     * bodies persisting concurrently. Does not wait for in-flight bodies
     * on shutdown — a half-run pass simply parks at its current phase.
     */
    @Bean(name = REVIEW_EXECUTOR)
    public Executor reviewExecutor()
    {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("review-pass-");
        executor.setCorePoolSize(REVIEW_EXECUTOR_CORE_POOL_SIZE);
        executor.setMaxPoolSize(REVIEW_EXECUTOR_MAX_POOL_SIZE);
        executor.setQueueCapacity(REVIEW_EXECUTOR_QUEUE_CAPACITY);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(REVIEW_EXECUTOR_AWAIT_TERMINATION_SECONDS);
        executor.initialize();
        return executor;
    }

    private static ExecutorService newVirtualThreadPerTaskExecutor(String namePrefix)
    {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(namePrefix, 0).factory());
    }
}
