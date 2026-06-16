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
package com.bytequay.app.beans.trace;

/**
 * One option on the next-possible line under the stepper.
 *
 * @param trigger the destination phase name (a stable identifier)
 * @param label   friendly node name the transition would append
 * @param cond    synthetic human condition (e.g. "on CI green")
 */
public record NextPossible(
        String trigger,
        String label,
        String cond)
{
}
