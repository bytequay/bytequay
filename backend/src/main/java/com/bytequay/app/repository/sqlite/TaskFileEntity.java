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
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "task_files")
class TaskFileEntity
{
    @EmbeddedId
    private TaskFileKey id;

    @Column(name = "operation", nullable = false)
    private String operation;

    @Column(name = "count", nullable = false)
    private int count;

    @Column(name = "lines_added", nullable = false)
    private int linesAdded;

    @Column(name = "lines_removed", nullable = false)
    private int linesRemoved;

    @Column(name = "last_touched_ms", nullable = false)
    private long lastTouchedMs;

    TaskFileKey getId() { return id; }
    void setId(TaskFileKey id) { this.id = id; }

    String getOperation() { return operation; }
    void setOperation(String operation) { this.operation = operation; }

    int getCount() { return count; }
    void setCount(int count) { this.count = count; }

    int getLinesAdded() { return linesAdded; }
    void setLinesAdded(int linesAdded) { this.linesAdded = linesAdded; }

    int getLinesRemoved() { return linesRemoved; }
    void setLinesRemoved(int linesRemoved) { this.linesRemoved = linesRemoved; }

    long getLastTouchedMs() { return lastTouchedMs; }
    void setLastTouchedMs(long lastTouchedMs) { this.lastTouchedMs = lastTouchedMs; }

    @Embeddable
    static final class TaskFileKey
            implements Serializable
    {
        @Column(name = "task_id", nullable = false)
        private String taskId;

        @Column(name = "path", nullable = false)
        private String path;

        TaskFileKey() {}

        TaskFileKey(String taskId, String path)
        {
            this.taskId = taskId;
            this.path = path;
        }

        String getTaskId() { return taskId; }
        void setTaskId(String taskId) { this.taskId = taskId; }

        String getPath() { return path; }
        void setPath(String path) { this.path = path; }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TaskFileKey that)) {
                return false;
            }
            return Objects.equals(taskId, that.taskId)
                    && Objects.equals(path, that.path);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(taskId, path);
        }
    }
}
