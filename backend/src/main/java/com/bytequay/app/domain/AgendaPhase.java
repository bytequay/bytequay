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

/**
 * One entry on a review pass's Lead-driven agenda — the agenda checklist of
 * phases the Lead sets at kickoff ({@code set_agenda}) and ticks
 * through with {@code mark_phase_in_progress} / {@code mark_phase_done}
 * as the pass runs. Persisted on {@code review_passes.agenda_json} as
 * a JSON array of {@code {id, title, status}} objects.
 *
 * @param id     stable across reruns, e.g. {@code "p1"},
 *               {@code "p_consensus"} — the handle the Lead's
 *               mark-phase tools address.
 * @param title  human-readable label the agenda widget renders, e.g.
 *               "Run 5 parallel finder agents".
 * @param status where the phase currently stands.
 */
public record AgendaPhase(
        String id,
        String title,
        AgendaPhaseStatus status)
{
}
