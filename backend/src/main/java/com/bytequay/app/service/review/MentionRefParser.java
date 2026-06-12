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

import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewParticipantKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parser for the panel's {@code @mention} / {@code #ref} addressing
 * grammar. No I/O — it turns a message body into the set of mention
 * labels and structured refs it carries, and {@link #resolveMentions}
 * maps those labels onto seated participant ids.
 *
 * <ul>
 *   <li>{@code @label} — a reviewer's display label, plus {@code @you}
 *       (the human) and {@code @panel} (broadcast to every reviewer).
 *       Case-insensitive, word-boundary anchored.</li>
 *   <li>{@code #msg-<id>} / {@code #finding-<id>} — a specific review
 *       message or finding row. {@code #cp-<id>} is reserved for
 *       checkpoint integration and is intentionally not parsed here.</li>
 * </ul>
 */
public final class MentionRefParser
{
    private MentionRefParser() {}

    /** Broadcast label addressing every reviewer on the panel. */
    public static final String PANEL = "panel";
    /** Label addressing the human seat. */
    public static final String YOU = "you";

    /** A parsed {@code #ref}. {@code kind} is {@code "msg"} or
     *  {@code "finding"}. */
    public record Ref(String kind, String targetId) {}

    /** Parse result: the raw mention labels (lower-cased, de-duplicated,
     *  in first-seen order) and the structured refs. */
    public record Parsed(List<String> mentionLabels, List<Ref> refs) {}

    // @label — not preceded by a word char or a second @ (so emails and
    // @@ don't match); label is letters/digits/dash/underscore.
    private static final Pattern MENTION = Pattern.compile("(?<![\\w@])@([A-Za-z0-9_-]+)");
    // #msg-<id> / #finding-<id> — id is uuid-ish. #cp- and bare #word
    // don't match.
    private static final Pattern REF =
            Pattern.compile("(?<![\\w#])#(msg|finding)-([A-Za-z0-9-]+)", Pattern.CASE_INSENSITIVE);

    public static Parsed parse(String text)
    {
        if (text == null || text.isEmpty()) {
            return new Parsed(List.of(), List.of());
        }
        List<String> mentions = new ArrayList<>();
        Matcher mm = MENTION.matcher(text);
        while (mm.find()) {
            String label = mm.group(1).toLowerCase(Locale.ROOT);
            if (!mentions.contains(label)) {
                mentions.add(label);
            }
        }
        List<Ref> refs = new ArrayList<>();
        Matcher rm = REF.matcher(text);
        while (rm.find()) {
            Ref ref = new Ref(rm.group(1).toLowerCase(Locale.ROOT), rm.group(2));
            if (!refs.contains(ref)) {
                refs.add(ref);
            }
        }
        return new Parsed(List.copyOf(mentions), List.copyOf(refs));
    }

    /**
     * Resolve raw mention labels onto seated participant ids:
     * {@code @you} → every HUMAN seat, {@code @panel} → every REVIEWER
     * seat, and any other label → the reviewer seat whose display label
     * matches (its first token equals the label, or it starts with it —
     * so {@code @gpt} reaches "GPT-5" and {@code @claude} reaches
     * "Claude (Anthropic)"). Unknown labels resolve to nothing. The
     * result is de-duplicated, in seat order.
     */
    public static List<String> resolveMentions(List<String> labels, List<ReviewParticipant> participants)
    {
        if (labels == null || labels.isEmpty() || participants == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (ReviewParticipant p : participants) {
            if (mentionHits(labels, p) && !ids.contains(p.id())) {
                ids.add(p.id());
            }
        }
        return List.copyOf(ids);
    }

    private static boolean mentionHits(List<String> labels, ReviewParticipant p)
    {
        for (String label : labels) {
            if (PANEL.equals(label) && p.kind() == ReviewParticipantKind.REVIEWER) {
                return true;
            }
            if (YOU.equals(label) && p.kind() == ReviewParticipantKind.HUMAN) {
                return true;
            }
            if (p.kind() == ReviewParticipantKind.REVIEWER && labelMatchesSeat(label, p.personaLabel())) {
                return true;
            }
        }
        return false;
    }

    private static boolean labelMatchesSeat(String label, String personaLabel)
    {
        if (personaLabel == null || personaLabel.isBlank()) {
            return false;
        }
        String norm = personaLabel.toLowerCase(Locale.ROOT).strip();
        String firstToken = norm.split("[\\s(]", 2)[0];
        return norm.equals(label) || firstToken.equals(label) || norm.startsWith(label);
    }

    /** Encode a ref for the {@code refs} JSON column ({@code kind:id}). */
    public static String encodeRef(Ref ref)
    {
        return ref.kind() + ":" + ref.targetId();
    }
}
