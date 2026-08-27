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
package com.bytequay.app.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/** The bounded batch walk shared by the jobs that drive long-running runs forward. */
public final class MonitorLoop
{
    private final Logger log;
    private final String what;
    private final AtomicBoolean running = new AtomicBoolean();

    public MonitorLoop(Class<?> job, String what)
    {
        this.log = LoggerFactory.getLogger(requireNonNull(job, "job is null"));
        this.what = requireNonNull(what, "what is null");
    }

    /** Runs one pass, unless a previous pass is still running. */
    public void pass(Runnable body)
    {
        requireNonNull(body, "body is null");
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            body.run();
        }
        finally {
            running.set(false);
        }
    }

    /**
     * Advances every selected run.
     *
     * <p>One unhealthy run must not stop the others, and the service has already
     * recorded whatever it could observe, so a failed transition is logged and
     * the walk continues.
     */
    public <I> void advanceEach(List<I> ids, Consumer<I> advance)
    {
        requireNonNull(ids, "ids is null");
        requireNonNull(advance, "advance is null");
        for (I id : ids) {
            try {
                advance.accept(id);
            }
            catch (RuntimeException failure) {
                log.warn("Cannot advance {} {}", what, id, failure);
            }
        }
    }
}
