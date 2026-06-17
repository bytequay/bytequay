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
 * The {@code state} GitHub returns for a submitted pull-request review.
 * These flow in from the API and live as plain strings (review verdict
 * maps, {@code PrReviewState.state()}), so they stay {@code String}
 * constants rather than an enum — the value is only ever compared, never
 * given behavior, and an enum would force parsing at every API boundary.
 * Centralising the spelling kills the magic strings the PR services
 * compared against and removes the typo risk.
 *
 * <p>Distinct from {@link ReviewVerdict}, which is ByteQuay's <em>outgoing</em>
 * suggested action ({@code APPROVE} / {@code REQUEST_CHANGES} / {@code COMMENT}).
 */
public final class GithubReviewState
{
    private GithubReviewState() {}

    public static final String APPROVED = "APPROVED";
    public static final String CHANGES_REQUESTED = "CHANGES_REQUESTED";
    public static final String COMMENTED = "COMMENTED";
    public static final String DISMISSED = "DISMISSED";
}
