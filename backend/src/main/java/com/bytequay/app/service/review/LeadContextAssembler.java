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

import com.bytequay.app.domain.AgendaPhase;
import com.bytequay.app.domain.ReviewMessage;
import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewParticipantKind;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.repository.ReviewStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Builds the Lead's view of the pass: unlike a reviewer seat, the
 * Lead sees the FULL transcript (same projection the watcher gets),
 * plus the roster with dispatchable participant ids, the live agenda,
 * and a bounded diff slice. The Lead is the only model-backed role
 * with this visibility — that asymmetry is what lets it synthesize
 * while the seats stay isolated.
 */
@Component
public class LeadContextAssembler
{
    /** Diff slice inlined into the Lead's context. Smaller than a
     *  seat's (the Lead synthesizes; seats verify) — it can pull more
     *  via the read tools. */
    private static final int MAX_INLINE_DIFF_CHARS = 30_000;

    private final ReviewStore reviewStore;
    private final ReviewDiffCache diffCache;

    public LeadContextAssembler(ReviewStore reviewStore, ReviewDiffCache diffCache)
    {
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
        this.diffCache = requireNonNull(diffCache, "diffCache is null");
    }

    /** One entry of the Lead's conversation view. */
    public record LeadMessage(String role, String text)
    {
    }

    /** The pass header the Lead's first user message carries: PR ref,
     *  roster with participant ids, agenda state, findings snapshot,
     *  and the diff slice. */
    public String passHeader(ReviewPass pass)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Review pass over ").append(pass.repoFullName())
                .append('#').append(pass.prNumber())
                .append(" at ").append(pass.headSha() == null ? "(unknown sha)" : pass.headSha())
                .append(".\n\nPanel roster:\n");
        for (ReviewParticipant p : reviewStore.listParticipantsForPass(pass.id())) {
            if (p.kind() == ReviewParticipantKind.REVIEWER) {
                sb.append("- ").append(p.personaLabel())
                        .append(" (participant_id=").append(p.id()).append(")\n");
            }
        }
        List<AgendaPhase> agenda = AgendaJsonCodec.parse(pass.agendaJson());
        if (agenda.isEmpty()) {
            sb.append("\nNo agenda set yet — set one with set_agenda.\n");
        }
        else {
            sb.append("\nAgenda:\n");
            for (AgendaPhase phase : agenda) {
                sb.append("- [").append(phase.status().jsonValue()).append("] ")
                        .append(phase.id()).append(": ").append(phase.title()).append('\n');
            }
        }
        var findings = reviewStore.listFindingsForPass(pass.id());
        if (!findings.isEmpty()) {
            sb.append("\nFindings so far:\n");
            for (var f : findings) {
                sb.append("- finding_id=").append(f.id())
                        .append(" [").append(f.status().dbValue())
                        .append('/').append(f.severity().dbValue()).append("] ")
                        .append(f.path() == null ? "(whole PR)" : f.path())
                        .append(f.line() == null ? "" : ":" + f.line())
                        .append(" — ").append(truncate(f.body(), 200)).append('\n');
            }
        }
        String diff = diffCache.diffFor(pass);
        if (diff.length() > MAX_INLINE_DIFF_CHARS) {
            diff = diff.substring(0, MAX_INLINE_DIFF_CHARS)
                    + "\n… [diff truncated — use get_pr_diff(path) for specific files]";
        }
        sb.append("\nUnified diff:\n```diff\n").append(diff).append("\n```");
        return sb.toString();
    }

    /** The full transcript as the Lead's conversation: its own rows
     *  read back as assistant turns, everyone else's as labelled user
     *  turns, oldest first. */
    public List<LeadMessage> transcript(ReviewPass pass, String leadParticipantId)
    {
        Map<String, String> labels = new HashMap<>();
        for (ReviewParticipant p : reviewStore.listParticipantsForPass(pass.id())) {
            labels.put(p.id(), p.personaLabel());
        }
        List<LeadMessage> out = new ArrayList<>();
        for (ReviewMessage m : reviewStore.listMessagesForPass(pass.id())) {
            if (m.participantId().equals(leadParticipantId)) {
                out.add(new LeadMessage("assistant", m.body()));
            }
            else {
                out.add(new LeadMessage("user",
                        "[" + labels.getOrDefault(m.participantId(), "panel") + "] " + m.body()));
            }
        }
        return out;
    }

    private static String truncate(String text, int max)
    {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }
}
