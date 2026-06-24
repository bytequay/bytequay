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
package com.bytequay.app.service.signal;

import com.bytequay.app.domain.ThreadSignal;
import com.bytequay.app.repository.ThreadSignalStore;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Strings.nullToEmpty;
import static java.util.Objects.requireNonNull;

@Service
public class ThreadSignalServiceImpl
        implements ThreadSignalService
{
    private static final Set<String> SOURCE_KINDS = Set.of("agent", "system", "github");
    private static final Set<String> ICON_KINDS = Set.of("info", "success", "warn", "alert");

    private final ThreadSignalStore store;

    public ThreadSignalServiceImpl(ThreadSignalStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    @Override
    public ThreadSignal record(
            String threadId, String taskId, String sourceKind, String iconKind,
            String title, String body, String sourceUrl)
    {
        String threadIdValue = nullToEmpty(threadId).strip();
        String titleValue = nullToEmpty(title).strip();
        if (threadIdValue.isEmpty()) {
            throw status(400, "threadId is required");
        }
        if (titleValue.isEmpty()) {
            throw status(400, "title is required");
        }
        if (!SOURCE_KINDS.contains(sourceKind)) {
            throw status(400, "invalid sourceKind: " + sourceKind);
        }
        if (!ICON_KINDS.contains(iconKind)) {
            throw status(400, "invalid iconKind: " + iconKind);
        }
        ThreadSignal signal = new ThreadSignal(
                UUID.randomUUID().toString(),
                threadIdValue,
                emptyToNull(nullToEmpty(taskId).strip()),
                sourceKind,
                iconKind,
                titleValue,
                emptyToNull(nullToEmpty(body).strip()),
                emptyToNull(nullToEmpty(sourceUrl).strip()),
                Instant.now(),
                /* readAt */ null);
        return store.save(signal);
    }

    @Override
    public List<ThreadSignal> list(String threadId)
    {
        return store.findByThread(nullToEmpty(threadId).strip());
    }

    @Override
    public void markRead(String id)
    {
        Optional<ThreadSignal> found = store.findById(nullToEmpty(id).strip());
        if (found.isEmpty() || found.get().readAt() != null) {
            return;
        }
        ThreadSignal s = found.get();
        store.save(new ThreadSignal(
                s.id(), s.threadId(), s.taskId(), s.sourceKind(), s.iconKind(),
                s.title(), s.body(), s.sourceUrl(), s.createdAt(), Instant.now()));
    }

    private static ResponseStatusException status(int code, String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(code), message);
    }
}
