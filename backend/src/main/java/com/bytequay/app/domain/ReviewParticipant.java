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
 * One member of a {@link ReviewPass}'s panel — moderator, a reviewer
 * credential, or the human orchestrator. Created at pass-startup
 * time and never edited; participants are immutable per the design.
 *
 * @param credentialId reference to the AI credential backing the
 *                     reviewer; null for the moderator and human
 *                     participants (which don't call a model).
 * @param personaLabel display name in the panel UI — "Claude",
 *                     "GPT-5", "You", "Moderator".
 * @param color        optional hex string for the persona bubble;
 *                     null when the renderer should pick a default.
 */
public record ReviewParticipant(
        String id,
        String reviewPassId,
        ReviewParticipantKind kind,
        String credentialId,
        String personaLabel,
        String model,
        String color,
        Instant createdAt)
{
}
