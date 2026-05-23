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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "thread_settings")
class ThreadSettingsEntity
{
    @Id
    @Column(name = "thread_id", nullable = false)
    private String threadId;

    @Column(name = "max_running_tasks")
    private Integer maxRunningTasks;

    @Column(name = "soft_cost_usd_milli")
    private Integer softCostUsdMilli;

    @Column(name = "hard_cost_usd_milli")
    private Integer hardCostUsdMilli;

    @Column(name = "prompt_addendum")
    private String promptAddendum;

    @Column(name = "updated_at_ms", nullable = false)
    private long updatedAtMs;

    String getThreadId() { return threadId; }
    void setThreadId(String threadId) { this.threadId = threadId; }

    Integer getMaxRunningTasks() { return maxRunningTasks; }
    void setMaxRunningTasks(Integer maxRunningTasks) { this.maxRunningTasks = maxRunningTasks; }

    Integer getSoftCostUsdMilli() { return softCostUsdMilli; }
    void setSoftCostUsdMilli(Integer softCostUsdMilli) { this.softCostUsdMilli = softCostUsdMilli; }

    Integer getHardCostUsdMilli() { return hardCostUsdMilli; }
    void setHardCostUsdMilli(Integer hardCostUsdMilli) { this.hardCostUsdMilli = hardCostUsdMilli; }

    String getPromptAddendum() { return promptAddendum; }
    void setPromptAddendum(String promptAddendum) { this.promptAddendum = promptAddendum; }

    long getUpdatedAtMs() { return updatedAtMs; }
    void setUpdatedAtMs(long updatedAtMs) { this.updatedAtMs = updatedAtMs; }
}
