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

import com.bytequay.app.beans.signal.ThreadSignalDto;
import com.bytequay.app.service.signal.ThreadSignalServiceImpl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for the per-thread passive signal feed (the trunk's
 * Notifications tab). Read the stream, then mark a row read when the user
 * opens it. Kept separate from {@code NotificationController}, which
 * serves the actionable approval gates.
 */
@RestController
public class ThreadSignalController
{
    private final ThreadSignalServiceImpl signals;

    public ThreadSignalController(ThreadSignalServiceImpl signals)
    {
        this.signals = requireNonNull(signals, "signals is null");
    }

    @GetMapping("/api/threads/{threadId}/signals")
    public List<ThreadSignalDto> list(@PathVariable String threadId)
    {
        return signals.list(threadId).stream().map(ThreadSignalDto::from).toList();
    }

    @PostMapping("/api/signals/{id}/read")
    public void markRead(@PathVariable String id)
    {
        signals.markRead(id);
    }
}
