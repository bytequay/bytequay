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

import com.bytequay.app.service.workspaces.SyncRunStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static java.util.Objects.requireNonNull;

/**
 * The live agent output of one sync run. Kept off the workspace-scoped
 * controller so the renderer's stream bridge can address it by run id alone,
 * the same shape as the thread and stage streams it already speaks.
 */
@RestController
public class UpstreamSyncStreamController
{
    private static final Logger log = LoggerFactory.getLogger(UpstreamSyncStreamController.class);
    /** A pick can compile for minutes; the bridge reconnects if this expires. */
    private static final long STREAM_TIMEOUT_MS = 30 * 60 * 1000L;

    private final SyncRunStream stream;

    public UpstreamSyncStreamController(SyncRunStream stream)
    {
        this.stream = requireNonNull(stream, "stream is null");
    }

    @GetMapping(value = "/api/upstream-cherry-picks/{jobId}/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String jobId)
    {
        return open(jobId);
    }

    private SseEmitter open(String key)
    {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        Runnable unsubscribe = stream.subscribe(key, line -> {
            try {
                emitter.send(SseEmitter.event().name("line").data(line));
            }
            catch (IOException gone) {
                // Client left. Surfacing it lets the publisher drop this listener.
                throw new IllegalStateException("sync run stream closed", gone);
            }
        });
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(() -> {
            unsubscribe.run();
            emitter.complete();
        });
        emitter.onError(failure -> {
            log.debug("agent stream {} ended: {}", key, failure.getMessage());
            unsubscribe.run();
        });
        return emitter;
    }
}
