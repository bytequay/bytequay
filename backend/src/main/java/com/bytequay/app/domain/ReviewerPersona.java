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
 * A user-defined reviewer voice — a (name, prompt, role) bundle the
 * Start Review dialog can pick from. Provider-agnostic: each pass
 * chooses which LLM provider runs the persona, so the same persona
 * can be served by different providers on different passes.
 *
 * <p>Stored in the {@code reviewer_personas} table; the Start Review
 * dialog reads {@link ReviewerPersonaRole#LEAD} and
 * {@link ReviewerPersonaRole#REVIEWER} entries to populate its picker.
 *
 * @param id              opaque uuid; stable identifier across edits
 * @param name            short label shown on chips ("Trino", "David")
 * @param systemPrompt    the reviewing voice — flows into the
 *                        per-reviewer system message on each pass
 * @param role            governs whether this persona is the lead
 *                        (drafts consensus) or one of N reviewers
 * @param active          soft-delete flag — inactive personas don't
 *                        show in the picker but remain referenceable
 *                        from prior findings
 * @param createdAt       wall-clock at first save
 * @param updatedAt       wall-clock at most recent save
 */
public record ReviewerPersona(
        String id,
        String name,
        String systemPrompt,
        ReviewerPersonaRole role,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {}
