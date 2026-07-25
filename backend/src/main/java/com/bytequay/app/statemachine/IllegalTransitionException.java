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
package com.bytequay.app.statemachine;

/**
 * An edge the {@link StateMachine}'s graph does not allow. Carries the
 * machine name, entity id, and both states so a log line or 409 mapping
 * needs no extra context.
 */
public class IllegalTransitionException
        extends RuntimeException
{
    private final String machineName;
    private final Object entityId;
    private final Object from;
    private final Object to;

    public IllegalTransitionException(String machineName, Object entityId, Object from, Object to)
    {
        super("%s %s cannot transition from %s to %s".formatted(machineName, entityId, from, to));
        this.machineName = machineName;
        this.entityId = entityId;
        this.from = from;
        this.to = to;
    }

    public String machineName()
    {
        return machineName;
    }

    public Object entityId()
    {
        return entityId;
    }

    public Object from()
    {
        return from;
    }

    public Object to()
    {
        return to;
    }
}
