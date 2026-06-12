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

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * The in-memory roster a pass run threads through the lead + seat
 * compositions: which participant row maps to which provider and
 * persona voice. Persona prompts are configuration (reviewer_personas
 * rows picked in the dialog), not transcript — they ride here rather
 * than being persisted per participant.
 *
 * @param seats one entry per model-backed participant (the lead and
 *              every reviewer seat).
 */
public record PanelSeatConfig(List<Seat> seats)
{
    public PanelSeatConfig
    {
        seats = List.copyOf(requireNonNull(seats, "seats is null"));
    }

    /**
     * @param participantId  the {@code review_participants} row id.
     * @param providerId     roster provider id ({@code claude} /
     *                       {@code openai} / {@code deepseek}).
     * @param personaPrompt  the reviewing voice, or null for the
     *                       provider's plain self.
     * @param displayLabel   the participant's persona label.
     * @param lead           true for the panel lead seat.
     */
    public record Seat(
            String participantId,
            String providerId,
            String personaPrompt,
            String displayLabel,
            boolean lead)
    {
    }

    public Optional<Seat> byParticipantId(String participantId)
    {
        return seats.stream()
                .filter(s -> s.participantId().equals(participantId))
                .findFirst();
    }

    public Optional<Seat> leadSeat()
    {
        return seats.stream().filter(Seat::lead).findFirst();
    }

    public List<Seat> reviewerSeats()
    {
        return seats.stream().filter(s -> !s.lead()).toList();
    }
}
