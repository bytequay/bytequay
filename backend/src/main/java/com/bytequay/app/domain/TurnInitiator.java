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
 * Who set a {@link ThreadTurn} in motion. Stamped at enqueue and
 * carried for the turn's life so a later decision point — notably the
 * tool-approval gate — can tell whether a human is watching.
 *
 * <p>{@code attended} is {@code true} when a person drove the turn
 * (composer send, task creation) and {@code false} when an automated
 * trigger did (the CI auto-fix coordinator). The autonomy envelope
 * uses this to decide whether a permission prompt makes sense — an
 * unattended turn has no one to click Allow, so an out-of-bounds
 * request escalates to a needs-attention notification instead of
 * stalling on a prompt.
 *
 * <p>{@code source} names the trigger (e.g. {@code "user"},
 * {@code "auto-fix-ci-fail"}) for provenance and debugging.
 */
public record TurnInitiator(boolean attended, String source)
{
    /** Default attended initiator for a human-driven turn. */
    public static TurnInitiator user()
    {
        return new TurnInitiator(true, "user");
    }

    /** Attended turn from a named human-driven source. */
    public static TurnInitiator attended(String source)
    {
        return new TurnInitiator(true, source);
    }

    /** Unattended turn from a named automated source. */
    public static TurnInitiator unattended(String source)
    {
        return new TurnInitiator(false, source);
    }
}
