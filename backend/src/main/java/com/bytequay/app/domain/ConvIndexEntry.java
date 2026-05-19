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
 * One row of the floating conversation index — a single user prompt
 * derived from {@code task_messages}.
 *
 * <p>The preview is a server-side rendering of the prompt's text:
 * leading whitespace stripped, internal whitespace collapsed to
 * single spaces, multi-line input reduced to the first non-empty
 * line, and a hard 80-char ellipsis cap so the panel can render
 * rows in a fixed-width column without per-row measurement.
 *
 * <p>{@code seq} doubles as the click-to-scroll anchor: the
 * structured-conversation renderer tags each user message row with
 * {@code data-seq=<seq>}, and the index's click handler runs
 * {@code scrollIntoView()} on the matching element.
 */
public record ConvIndexEntry(
        long seq,
        String preview,
        long tsMs)
{
}
