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
package com.bytequay.app.service.inspector;

/**
 * The eight sections an agent's prompt context is sliced into for
 * the read-only inspector, in the exact order the provider
 * serialises them. The order is load-bearing — the prefix cache
 * hashes the bytes top-to-bottom up through the first dynamic
 * region, so any reshuffle here breaks cache hits across turns.
 *
 * <ul>
 *   <li>① {@link #TOOLS} — skill tools first, then action tools.</li>
 *   <li>② {@link #ROLE} — the role-skill body frozen onto the task
 *       at creation (or the trunk template for a trunk scope).</li>
 *   <li>③ {@link #BRAIN} — the workspace {@code memoryMd} and the
 *       repo {@code REPO.md} analog.</li>
 *   <li>④ {@link #CONCEPT_PREAMBLE} — the top-N concept one-liners
 *       baked in via {@code @Concept}.</li>
 *   <li>⑤ {@link #SKILL_MANIFEST} — top-N skill briefs the agent can
 *       load on demand.</li>
 *   <li>⑥ {@link #MEMORY} — applied memory items rendered to the
 *       canonical markdown.</li>
 *   <li>⑦ {@link #HISTORY} — append-only conversation history,
 *       oldest first.</li>
 *   <li>⑧ {@link #NEW_TURN} — the new user turn at the tail.</li>
 * </ul>
 *
 * <p>Sections ②–⑥ glue into one system content array on the wire;
 * sections ⑦–⑧ become messages. The viewer keeps them split for
 * the section view but renders them together for the full-request
 * view so the user sees both shapes.
 */
public enum SectionKind
{
    TOOLS,
    ROLE,
    BRAIN,
    CONCEPT_PREAMBLE,
    SKILL_MANIFEST,
    MEMORY,
    HISTORY,
    NEW_TURN
}
