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
 * Read-only projection for the dashboard list: a watched {@link PR} plus its
 * {@link PR.PRSyncSnapshot} and {@link PRTriageState}, flattened into one DTO
 * at the wire boundary. {@code pr.githubSync()} is never null here — {@link
 * com.bytequay.app.repository.PRStore#findDashboardEntries} only returns rows
 * with a non-null {@code watch_reason}. {@code triage} defaults to {@link
 * PRTriageState#empty} for a PR never touched from the dashboard.
 */
public record PRDashboardEntry(PR pr, PRTriageState triage) {}
