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

import com.bytequay.app.domain.ReviewMessage;
import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.repository.ReviewStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Builds one reviewer seat's FILTERED view of the pass transcript.
 *
 * <p><strong>This filter is a safety property, not an optimization.</strong>
 * A seat's context contains ONLY:
 *
 * <ul>
 *   <li>(a) messages whose {@code mentions} include the seat,</li>
 *   <li>(b) messages the seat itself authored,</li>
 *   <li>(c) the bodies of {@code #ref}-quoted messages — but only when
 *       the quoting message @-mentions this seat (the Lead can quote
 *       another reviewer FOR me; nothing else of that reviewer's
 *       stream leaks).</li>
 * </ul>
 *
 * Other reviewers' messages, the Lead's coordination chatter to other
 * seats, and the agenda are all excluded. This prevents anchoring on
 * whoever spoke first, scales linearly with panel size, and keeps each
 * seat's prefix bytes bounded for cache reuse.
 * {@code TestSeatContextIsolation} locks the property on every build —
 * change this class only with that suite green.
 */
@Component
public class SeatContextAssembler
{
    private final ReviewStore reviewStore;

    public SeatContextAssembler(ReviewStore reviewStore)
    {
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
    }

    /** One entry of the seat's conversation view. {@code role} is
     *  "assistant" for the seat's own prior messages and "user" for
     *  everything addressed to it. */
    public record SeatMessage(String role, String text)
    {
    }

    /**
     * Assemble the seat's filtered history, oldest first.
     *
     * @param excludeMessageId message to leave out — the caller passes
     *        the just-persisted dispatch directive's id so it can ride
     *        as the turn's new user message instead of duplicating
     *        into history. Null excludes nothing.
     */
    public List<SeatMessage> assemble(ReviewPass pass, String participantId, String excludeMessageId)
    {
        requireNonNull(pass, "pass is null");
        requireNonNull(participantId, "participantId is null");

        Map<String, String> labels = new HashMap<>();
        for (ReviewParticipant p : reviewStore.listParticipantsForPass(pass.id())) {
            labels.put(p.id(), p.personaLabel());
        }

        List<SeatMessage> out = new ArrayList<>();
        for (ReviewMessage m : reviewStore.listMessagesForPass(pass.id())) {
            if (m.id().equals(excludeMessageId)) {
                continue;
            }
            if (participantId.equals(m.participantId())) {
                out.add(new SeatMessage("assistant", m.body()));
                continue;
            }
            if (m.mentions() == null || !m.mentions().contains(participantId)) {
                // Not mine, not addressed to me — invisible. This line
                // is the isolation property; do not weaken it.
                continue;
            }
            StringBuilder text = new StringBuilder();
            text.append('[').append(labels.getOrDefault(m.participantId(), "panel"))
                    .append("] ").append(m.body());
            appendRefBodies(m, labels, text);
            out.add(new SeatMessage("user", text.toString()));
        }
        return out;
    }

    /** Inline the bodies the message explicitly {@code #ref}-quoted.
     *  Only called for messages addressed to this seat, so a quote is
     *  the single sanctioned way other reviewers' words reach it. */
    private void appendRefBodies(ReviewMessage m, Map<String, String> labels, StringBuilder text)
    {
        if (m.refs() == null) {
            return;
        }
        for (String encoded : m.refs()) {
            int sep = encoded.indexOf(':');
            if (sep <= 0) {
                continue;
            }
            String kind = encoded.substring(0, sep);
            String id = encoded.substring(sep + 1);
            if ("msg".equals(kind)) {
                reviewStore.findMessageById(id).ifPresent(quoted -> text
                        .append("\n[quoted from ")
                        .append(labels.getOrDefault(quoted.participantId(), "panel"))
                        .append("]: ")
                        .append(quoted.body()));
            }
            else if ("finding".equals(kind)) {
                reviewStore.findFindingById(id).ifPresent(f -> text
                        .append("\n[finding #").append(f.id()).append("]: ")
                        .append(f.body()));
            }
        }
    }
}
