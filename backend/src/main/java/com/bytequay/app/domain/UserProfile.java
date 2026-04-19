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

public record UserProfile(
        String login,
        String name,
        String avatarUrl,
        String htmlUrl,
        int publicRepos,
        int followers,
        int following,
        String bio,
        String location,
        /** Free-form text from GitHub's "Company" field — may be null. */
        String company,
        /** Public email from GitHub — null if the user has hidden it. */
        String email,
        /** True iff the user has set up GitHub Sponsors. Sourced via GraphQL
         *  ({@code viewer.hasSponsorsListing}) since the REST API doesn't
         *  expose this. */
        boolean hasSponsors)
{}
