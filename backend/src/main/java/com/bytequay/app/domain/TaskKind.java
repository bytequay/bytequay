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
 * Decides who runs the agent loop for a {@link Task}. The frontend
 * never branches on this — the same {@code StreamEvent} shapes flow
 * through the renderer regardless. Backend pieces that DO branch are
 * spawning, resume-on-restart, source-of-truth, and permission-reply
 * delivery.
 *
 * <p>See the "Task kinds" section in
 * {@code docs/mockups/tasks-design.md} for the full table.
 */
public enum TaskKind
{
    /** Wraps an external CLI like {@code claude code}; its stdout is
     *  the source of {@code StreamEvent}s. The CLI runs the loop, calls
     *  its own tools, and writes its own JSONL log to disk. */
    CLI_AGENT,

    /** Runs the agent loop in-JVM, calling a model API directly and
     *  executing tools ourselves. Synthesizes the same
     *  {@code StreamEvent} shapes the cli_agent path emits. */
    LOGIC_LOOP,
}
