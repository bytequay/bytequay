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
package com.bytequay.app.service.pr;

import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.hosting.HostingFailure;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static java.util.Objects.requireNonNull;

/** In-flight reads disappear on restart, so their write revisions need no durable state. */
final class PullRequestReadGuard
{
    private final Map<PullRequestRef, State> states = new HashMap<>();
    private final Map<String, State> repositories = new HashMap<>();
    private final State discovery = new State(false);

    synchronized Read capture(PullRequestRef ref)
    {
        return capture(state(ref));
    }

    synchronized Read captureRepository(RepoRef repository)
    {
        return capture(repository(repository.fullName()));
    }

    synchronized Read captureDiscovery()
    {
        return capture(discovery);
    }

    private Read capture(State state)
    {
        state.requireReadable();
        return new Read(this, state, state.revision);
    }

    <T> T write(PullRequestRef ref, Supplier<T> action)
    {
        requireNonNull(action, "action is null");
        State state;
        State repository;
        synchronized (this) {
            state = state(ref);
            repository = repository(ref.repoFullName());
            state.requireReadable();
            state.writer = Thread.currentThread();
            state.depth++;
            state.revision++;
            repository.depth++;
            repository.revision++;
            discovery.depth++;
            discovery.revision++;
        }
        try {
            return action.get();
        }
        finally {
            synchronized (this) {
                state.revision++;
                if (--state.depth == 0) {
                    state.writer = null;
                }
                repository.depth--;
                repository.revision++;
                discovery.depth--;
                discovery.revision++;
            }
        }
    }

    private State state(PullRequestRef ref)
    {
        requireNonNull(ref, "ref is null");
        PullRequestRef normalized = parseRef(ref.repoFullName().toLowerCase(Locale.ROOT), ref.number());
        return states.computeIfAbsent(normalized, ignored -> new State(true));
    }

    private State repository(String fullName)
    {
        return repositories.computeIfAbsent(fullName.toLowerCase(Locale.ROOT), ignored -> new State(false));
    }

    record Read(PullRequestReadGuard guard, State state, long revision)
    {
        <T> T accept(Supplier<T> action)
        {
            synchronized (guard) {
                state.requireReadable();
                if (state.revision != revision) {
                    throw changed();
                }
                return action.get();
            }
        }
    }

    private static final class State
    {
        private final boolean ownerReads;
        private long revision;
        private Thread writer;
        private int depth;

        private State(boolean ownerReads)
        {
            this.ownerReads = ownerReads;
        }

        private void requireReadable()
        {
            if (depth > 0 && (!ownerReads || writer != Thread.currentThread())) {
                throw changed();
            }
        }
    }

    private static HostingFailure changed()
    {
        return new HostingFailure(REVISION_CONFLICT, "The pull request changed during this read; refresh to retry");
    }
}
