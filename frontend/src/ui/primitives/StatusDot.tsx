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

/** Stage / task / CI-check status. `active` + `planning` pulse; `future`
 *  is a dim dashed outline for a stage that hasn't been instantiated. */
export type StatusDotVariant = 'active' | 'planning' | 'sleep' | 'done' | 'future';

/**
 * A small round status indicator, reused across the sidebar stage
 * nesting, task-card status, and CI check rows. The single Layer 1
 * primitive behind every "is-running / done / future" dot in V3.
 */
export function StatusDot({ variant, title }: { variant: StatusDotVariant; title?: string }) {
  return <span className={`v3-dot v3-dot--${variant}`} title={title} aria-hidden />;
}
