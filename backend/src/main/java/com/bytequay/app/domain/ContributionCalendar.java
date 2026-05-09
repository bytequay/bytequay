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

import java.time.LocalDate;
import java.util.List;

/**
 * Last-12-months contribution heatmap for one user, sourced from GitHub's
 * GraphQL {@code contributionsCollection.contributionCalendar}. Powers the
 * "Your year in code" card on the home page.
 *
 * <p>{@code weeks} preserves GitHub's column structure — each week is one
 * vertical strip of up to seven {@link Day}s, ordered Sunday → Saturday.
 * The first and last weeks may have fewer than seven days if the rolling
 * 12-month window doesn't align cleanly to week boundaries.
 *
 * @param totalContributions total commits + PRs + reviews + issues counted
 *                           by GitHub for this calendar window
 * @param weeks              one week per column, oldest first
 */
public record ContributionCalendar(
        int totalContributions,
        List<Week> weeks)
{
    /**
     * One vertical strip in the heatmap grid.
     */
    public record Week(List<Day> days) {}

    /**
     * One cell in the heatmap.
     *
     * @param date              calendar day (ISO yyyy-MM-dd)
     * @param contributionCount commits + PRs + reviews + issues credited to this day
     * @param color             GitHub-supplied hex (e.g. {@code "#ebedf0"} for empty
     *                          through {@code "#216e39"} for the densest bucket).
     *                          Returned as-is so the frontend can match GitHub's
     *                          palette without a server-side bucket function.
     */
    public record Day(LocalDate date, int contributionCount, String color) {}
}
