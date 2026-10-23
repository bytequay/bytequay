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
package com.bytequay.app.team;

import java.util.Locale;
import java.util.regex.Pattern;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;

/** Selected identity from the locally cached repository contributor picker. */
public record TeamMemberInput(long githubId, String login)
{
    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,99}(?:\\[bot\\])?");

    public TeamMemberInput
    {
        checkArgument(githubId > 0, "GitHub user ID must be positive");
        checkText(login, "GitHub login");
        login = login.strip().toLowerCase(Locale.ROOT);
        checkArgument(LOGIN.matcher(login).matches(), "Enter a GitHub login, not a profile URL");
    }
}
