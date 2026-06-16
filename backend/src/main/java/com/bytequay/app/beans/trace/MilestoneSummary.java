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
 * One of the six canonical milestone buckets in the collapsed view.
 *
 * @param visits  distinct entries into this bucket (a {@code ×N} loop badge)
 * @param active  true when the current phase rolls up to this bucket
 * @param skipped true when this bucket was never entered yet a downstream
 *                bucket has been — a skip-by-omission shown dashed
 * @param position 1-based left-to-right order
 */
public record MilestoneSummary(
        String milestone,
        String label,
        int visits,
        boolean active,
        boolean skipped,
        int position)
{
}
