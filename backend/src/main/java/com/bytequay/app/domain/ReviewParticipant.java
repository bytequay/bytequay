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

import java.time.Instant;

/**
 * One member of a {@link ReviewPass}'s panel — the lead, a reviewer
 * credential, or the human orchestrator. Created at pass-startup
 * time; identity fields are immutable per the design, only the
 * budget-spend counter advances as the seat's turns are metered.
 *
 * @param credentialId reference to the AI credential backing the
 *                     reviewer; null for the human participant (and
 *                     for legacy lead rows that didn't call a model).
 * @param personaLabel display name in the panel UI — "Claude",
 *                     "GPT-5", "You", "Lead".
 * @param color        optional hex string for the persona bubble;
 *                     null when the renderer should pick a default.
 * @param budgetMilliUsdCap per-seat budget slice in milli-USD,
 *                     stamped at kickoff (pass cap / panel size by
 *                     default). 0 means "no per-seat bound" — legacy
 *                     rows and the human seat.
 * @param budgetMilliUsdSpent spend metered against the slice so far.
 */
public record ReviewParticipant(
        String id,
        String reviewPassId,
        ReviewParticipantKind kind,
        String credentialId,
        String personaLabel,
        String model,
        String color,
        Instant createdAt,
        long budgetMilliUsdCap,
        long budgetMilliUsdSpent)
{
    /** Seat without a budget slice — legacy call sites and the
     *  non-reviewer (human) rows. */
    public ReviewParticipant(
            String id,
            String reviewPassId,
            ReviewParticipantKind kind,
            String credentialId,
            String personaLabel,
            String model,
            String color,
            Instant createdAt)
    {
        this(id, reviewPassId, kind, credentialId, personaLabel, model, color,
                createdAt, 0L, 0L);
    }

    /** Copy with the spend advanced to {@code newSpent} milli-USD. */
    public ReviewParticipant withBudgetSpent(long newSpent)
    {
        return new ReviewParticipant(id, reviewPassId, kind, credentialId,
                personaLabel, model, color, createdAt, budgetMilliUsdCap, newSpent);
    }
}
