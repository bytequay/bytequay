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
package com.bytequay.persistence.sqlite.repository;

import com.bytequay.persistence.sqlite.entity.AgentReviewRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface AgentReviewRunJpaRepository
        extends JpaRepository<AgentReviewRunEntity, String>
{
    List<AgentReviewRunEntity> findByReviewIdOrderByOrdinalAsc(String reviewId);

    @Query(value = """
            WITH latest AS (
                SELECT id, ROW_NUMBER() OVER (PARTITION BY review_id ORDER BY ordinal DESC, id DESC) AS position,
                    MAX(CASE WHEN state = 'RUNNING' THEN 1 ELSE 0 END) OVER (PARTITION BY review_id) AS active
                FROM agent_review_run WHERE review_id IN :reviewIds
            )
            SELECT run.review_id AS reviewId, latest.active AS active, run.scope AS scope, run.state AS state, run.ended_at AS endedAt,
                json_array_length(run.commits_json) AS commits, json_array_length(run.diff_json) AS files,
                COALESCE((SELECT SUM(json_extract(file.value, '$.additions')) FROM json_each(run.diff_json) file), 0) AS additions,
                COALESCE((SELECT SUM(json_extract(file.value, '$.deletions')) FROM json_each(run.diff_json) file), 0) AS deletions
            FROM latest JOIN agent_review_run run ON run.id = latest.id WHERE latest.position = 1
            """, nativeQuery = true)
    List<RunOverview> summarizeRuns(@Param("reviewIds") Set<String> reviewIds);

    interface RunOverview
    {
        String getReviewId();

        int getActive();

        String getScope();

        String getState();

        String getEndedAt();

        int getCommits();

        int getFiles();

        long getAdditions();

        long getDeletions();
    }
}
