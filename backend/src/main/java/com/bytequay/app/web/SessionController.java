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

import com.bytequay.app.beans.session.SessionDto;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.service.runs.SessionProjectionService;
import com.bytequay.app.service.runs.SessionProjectionService.SessionProjection;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Canonical workspace-facing Session endpoints. */
@RestController
public class SessionController
{
    private static final long STREAM_TIMEOUT_MS = 30 * 60 * 1_000L;

    private final SessionProjectionService sessions;

    public SessionController(SessionProjectionService sessions)
    {
        this.sessions = requireNonNull(sessions, "sessions is null");
    }

    @GetMapping("/api/workspaces/{workspaceId}/sessions")
    public List<SessionDto> list(@PathVariable String workspaceId)
    {
        Instant now = Instant.now();
        return sessions.list(workspaceId).stream()
                .map(session -> dto(session, now))
                .toList();
    }

    @GetMapping("/api/sessions/{sessionId}")
    public SessionDto get(@PathVariable String sessionId)
    {
        return dto(sessions.require(sessionId), Instant.now());
    }

    @GetMapping(
            value = "/api/sessions/{sessionId}/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String sessionId)
    {
        sessions.require(sessionId);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        Thread.startVirtualThread(() -> streamUntilTerminal(sessionId, emitter));
        return emitter;
    }

    private void streamUntilTerminal(String id, SseEmitter emitter)
    {
        SessionDto previous = null;
        try {
            while (true) {
                SessionProjection session = sessions.require(id);
                AgentRun run = session.run();
                SessionDto current = dto(session, Instant.now());
                if (!current.equals(previous)) {
                    emitter.send(SseEmitter.event()
                            .name("session")
                            .id(Integer.toString(current.stepCursor()))
                            .data(current));
                    previous = current;
                }
                if (!run.isLive()) {
                    emitter.complete();
                    return;
                }
                Thread.sleep(500);
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        }
        catch (IOException | RuntimeException e) {
            emitter.completeWithError(e);
        }
    }

    private static SessionDto dto(SessionProjection session, Instant now)
    {
        return SessionDto.from(
                session.id(), session.run(), now, session.durableReview(),
                !session.typedV2() && !session.durableReview());
    }
}
