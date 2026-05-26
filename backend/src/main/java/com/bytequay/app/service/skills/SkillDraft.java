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
package com.bytequay.app.service.skills;

/**
 * Output of {@code LlmReviewer.draftSkill(prompt, scope)} — a
 * proposed library skill the user reviews + edits in the modal
 * before saving. The trigger is intentionally split out as its own
 * field because the description is what the agent matches on (the
 * "loads when …" line in the row).
 *
 * @param name        short user-facing label (≤ 6 words)
 * @param description the trigger phrase — what the agent matches on
 *                    when deciding whether to load this skill
 * @param body        markdown skill body — the actual instructions
 *                    that get loaded when the trigger matches
 */
public record SkillDraft(String name, String description, String body) {}
