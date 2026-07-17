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
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.runs.SessionControlService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static java.util.Objects.requireNonNull;

/** Canonical workspace-facing Session endpoints. */
@RestController
public class SessionController
{
    private static final long STREAM_TIMEOUT_MS = 30 * 60 * 1_000L;

    private final AgentRunService runs;
    private final SessionControlService controls;

    public SessionController(AgentRunService runs, SessionControlService controls)
    {
        this.runs = requireNonNull(runs, "runs is null");
        this.controls = requireNonNull(controls, "controls is null");
    }

    @GetMapping("/api/workspaces/{workspaceId}/sessions")
    public List<SessionDto> list(@PathVariable String workspaceId)
    {
        Instant now = Instant.now();
        return runs.findByWorkspace(workspaceId).stream()
                .filter(SessionDto::isPublic)
                .map(run -> SessionDto.from(run, now))
                .toList();
    }

    @GetMapping("/api/sessions/{sessionId}")
    public SessionDto get(@PathVariable String sessionId)
    {
        return dto(requireRun(sessionId));
    }

    @PostMapping("/api/sessions/{sessionId}/pause")
    public SessionDto pause(@PathVariable String sessionId)
    {
        return dto(controls.pause(sessionId));
    }

    @PostMapping("/api/sessions/{sessionId}/resume")
    public SessionDto resume(@PathVariable String sessionId)
    {
        return dto(controls.resume(sessionId));
    }

    @PostMapping("/api/sessions/{sessionId}/stop")
    public SessionDto stop(@PathVariable String sessionId)
    {
        return dto(controls.stop(sessionId));
    }

    @PostMapping("/api/sessions/{sessionId}/restart")
    public SessionDto restart(@PathVariable String sessionId)
    {
        return dto(controls.restart(sessionId));
    }

    @GetMapping(
            value = "/api/sessions/{sessionId}/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String sessionId)
    {
        requireRun(sessionId);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        Thread.startVirtualThread(() -> streamUntilTerminal(sessionId, emitter));
        return emitter;
    }

    private void streamUntilTerminal(String id, SseEmitter emitter)
    {
        SessionDto previous = null;
        try {
            while (true) {
                AgentRun run = requireRun(id);
                SessionDto current = dto(run);
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

    private AgentRun requireRun(String id)
    {
        return runs.findById(id)
                .filter(SessionDto::isPublic)
                .orElseThrow(() -> new NoSuchElementException("no session: " + id));
    }

    private static SessionDto dto(AgentRun run)
    {
        return SessionDto.from(run, Instant.now());
    }
}
