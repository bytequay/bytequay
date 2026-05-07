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
 * @param upstreamRemoteName  Name of the git remote in the local clone
 *                            that points at github.com/{owner}/{repo}
 *                            when the user is using a fork-based
 *                            workflow. Null for direct clones (origin
 *                            already points at the watched repo) and
 *                            for not-yet-mapped repos.
 */
public record WatchedRepo(
        long id,
        String owner,
        String repo,
        int displayOrder,
        String localClonePath,
        String upstreamRemoteName)
{
    public String fullName()
    {
        return owner + "/" + repo;
    }
}
