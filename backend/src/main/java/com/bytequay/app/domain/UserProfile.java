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
 * GitHub user profile summary.
 *
 * @param company free-form text from GitHub's Company field.
 * @param email public email from GitHub, or null if hidden.
 * @param hasSponsors true iff the user has set up GitHub Sponsors.
 */
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
        String company,
        String email,
        boolean hasSponsors)
{}
