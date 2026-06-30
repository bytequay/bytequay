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
package com.bytequay.app.repository.sqlite;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface BacklogItemJpaRepository
        extends JpaRepository<BacklogItemEntity, String>
{
    List<BacklogItemEntity> findByThreadIdOrderByCreatedAtMsAsc(String threadId);

    /** Workspace-wide view — newest-first across every thread in the
     *  workspace. The service applies the status / thread / tag / search
     *  filters over the result. */
    List<BacklogItemEntity> findByWorkspaceIdOrderByCreatedAtMsDesc(String workspaceId);
}
