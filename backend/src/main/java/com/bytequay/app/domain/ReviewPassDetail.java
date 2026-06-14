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
package com.bytequay.app.domain;

import java.util.List;

/**
 * Aggregated view of a {@link ReviewPass} with its panel roster,
 * conversation transcript, and structured findings — the shape the
 * controller hands back to the panel UI so the frontend can render
 * everything in one round-trip.
 */
public record ReviewPassDetail(
        ReviewPass pass,
        /** The reviewed PR's title, resolved from the local PR cache at
         *  read time so the panel header can show it instead of a bare
         *  {@code repo#number}. Null when the PR isn't cached locally. */
        String prTitle,
        /** The Lead's agenda parsed from the pass row — the phase checklist
         *  the panel page renders above the transcript. Empty for
         *  passes without one. */
        List<AgendaPhase> agenda,
        List<ReviewParticipant> participants,
        List<ReviewMessage> messages,
        List<ReviewFinding> findings)
{
    /** Detail without an agenda — legacy call sites. */
    public ReviewPassDetail(
            ReviewPass pass,
            String prTitle,
            List<ReviewParticipant> participants,
            List<ReviewMessage> messages,
            List<ReviewFinding> findings)
    {
        this(pass, prTitle, List.of(), participants, messages, findings);
    }
}
