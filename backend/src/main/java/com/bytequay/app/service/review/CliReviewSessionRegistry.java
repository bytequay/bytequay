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
package com.bytequay.app.service.review;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the CLI provider session id per reviewer seat, so a CLI seat
 * resumes its own session — and thus keeps its prior-phase context —
 * across the phases of a pass.
 *
 * <p>Keyed by participant id (unique per pass), so entries from different
 * passes never collide. State is in-memory only: if the app restarts
 * mid-pass the session is lost, and a resume simply starts the CLI fresh —
 * the same graceful degradation as a dropped API turn.
 */
@Component
public class CliReviewSessionRegistry
{
    private final Map<String, String> sessionByParticipant = new ConcurrentHashMap<>();

    /** The session id to resume for this seat, or null on its first turn. */
    String get(String participantId)
    {
        return sessionByParticipant.get(participantId);
    }

    /** Record the session id the CLI announced, for the next turn to resume. */
    void put(String participantId, String sessionId)
    {
        sessionByParticipant.put(participantId, sessionId);
    }

    /** Forget a seat's session (e.g. when its pass ends). */
    void clear(String participantId)
    {
        sessionByParticipant.remove(participantId);
    }
}
