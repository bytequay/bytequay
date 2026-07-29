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
package com.bytequay.app.service.threads;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Fail-closed compatibility dependency for {@link ThreadRegistry}.
 * Historical registry APIs remain injectable for read projections, but the
 * removed shared agent runner cannot accept work or create a thread.
 */
final class RetiredAgentExecutor
        extends AbstractExecutorService
{
    private volatile boolean shutdown;

    @Override
    public void shutdown()
    {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow()
    {
        shutdown = true;
        return List.of();
    }

    @Override
    public boolean isShutdown()
    {
        return shutdown;
    }

    @Override
    public boolean isTerminated()
    {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
    {
        return shutdown;
    }

    @Override
    public void execute(Runnable command)
    {
        throw new RejectedExecutionException(
                "Legacy ThreadRegistry agent execution is retired");
    }
}
