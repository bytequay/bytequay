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
package com.bytequay.app.service.local.ds4;

/**
 * Spring event published whenever the lifecycle state transitions.
 * The metrics subsystem subscribes so it can pause / resume collection
 * cleanly; later phases will fan this onto an SSE stream for the
 * floating widget so polling drops out.
 *
 * <p>{@code from} is null on the boot transition (no prior state).
 */
public record Ds4StatusEvent(Ds4State from, Ds4Status to)
{
}
