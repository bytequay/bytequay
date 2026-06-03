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
package com.bytequay.app.service.concepts;

/**
 * Marker type that anchors the {@code trunk} concept. The trunk is
 * the long-lived assistant thread the user talks to about the
 * whole workspace — it owns workspace memory, fans out tasks, and
 * never edits code itself. There is no runtime class that
 * represents a "trunk" today (it's a way of using a
 * {@link com.bytequay.app.domain.Thread Thread}), so this empty
 * marker exists only to give the {@link Concept} annotation a
 * stable, reflectable home.
 *
 * <p>If a real Trunk type ever materialises, move the
 * {@code @Concept} onto it and delete this marker — the
 * registry's source field will update on the next startup scan.
 */
@Concept(
        name = "trunk",
        aka = {"trunk-thread", "control-thread"},
        kind = ConceptKind.NOUN,
        definition = "The long-lived assistant thread the user talks to about the whole "
                + "workspace. The trunk owns workspace memory, fans tasks out, and "
                + "never edits code itself — code edits happen inside per-task threads "
                + "the trunk launches.",
        examples = {
                "I told the trunk to draft a fix in a new task — the trunk created the "
                        + "task and pushed the work into it.",
                "The trunk's memory tracks the decision; the task's memory tracks the "
                        + "diff it produced."
        },
        relatedConcepts = {"thread", "task"})
public final class TrunkRole
{
    private TrunkRole() {}
}
