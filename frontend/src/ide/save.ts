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
/* eslint-disable import/no-unresolved -- Pinned VS Code service exports. */
import { getService, IFileService, ITextFileService } from '@codingame/monaco-vscode-api';
import { VSBuffer, readableToBuffer, streamToBuffer } from '@codingame/monaco-vscode-api/vscode/vs/base/common/buffer';
import { isReadableStream } from '@codingame/monaco-vscode-api/vscode/vs/base/common/stream';
import { FileOperationError, FileOperationResult, ETAG_DISABLED, toFileOperationResult } from '@codingame/monaco-vscode-api/vscode/vs/platform/files/common/files';
import { Emitter } from '@codingame/monaco-vscode-api/vscode/vs/base/common/event';
import type { URI } from '@codingame/monaco-vscode-api/vscode/vs/base/common/uri';
import type { IdeConnection } from './contracts';

/** Keep VS Code's encoding and stale-buffer check; ByteQuay owns the final compare-and-write under its operation claim. */
export async function coordinateSaves(connection: IdeConnection) {
  const files = await getService(IFileService);
  const textFiles = await getService(ITextFileService);
  const saved = new Emitter<{ resource: URI; text: string }>();
  const write = files.writeFile.bind(files);
  const coordinatedWrite: typeof files.writeFile = async (resource, value, options) => {
    if (resource.scheme !== 'vscode-remote') return write(resource, value, options);
    if (resource.authority !== connection.authority || !resource.path.startsWith(connection.root + '/')) {
      throw new FileOperationError('Save files inside the task working copy', FileOperationResult.FILE_PERMISSION_DENIED);
    }
    const current = await files.readFile(resource).catch((error): null => {
      if (toFileOperationResult(error) === FileOperationResult.FILE_NOT_FOUND && !options?.etag) return null;
      throw error;
    });
    if (current !== null && options?.etag !== undefined && options.etag !== ETAG_DISABLED && options.etag !== current.etag) {
      throw new FileOperationError('The file changed on disk. Compare your changes before saving.', FileOperationResult.FILE_MODIFIED_SINCE);
    }
    const bytes = value instanceof VSBuffer ? value : isReadableStream(value) ? await streamToBuffer(value) : readableToBuffer(value);
    if (bytes.byteLength > 2 * 1024 * 1024) throw new FileOperationError('The file is larger than 2 MiB', FileOperationResult.FILE_TOO_LARGE);
    const hash = current === null ? '-' : [...new Uint8Array(await crypto.subtle.digest('SHA-256', new Uint8Array(current.value.buffer)))].map(byte => byte.toString(16).padStart(2, '0')).join('');
    let binary = '';
    for (let offset = 0; offset < bytes.byteLength; offset += 8192) binary += String.fromCharCode(...bytes.buffer.subarray(offset, offset + 8192));
    try { await window.ideWorkbench.save(resource.path.slice(connection.root.length + 1), btoa(binary), hash); }
    catch (error) {
      if (String(error).includes('IDE_FILE_CHANGED')) throw new FileOperationError('The file changed on disk. Compare your changes before saving.', FileOperationResult.FILE_MODIFIED_SINCE);
      throw error;
    }
    return files.resolve(resource, { resolveMetadata: true });
  };
  files.writeFile = coordinatedWrite;
  // VS Code's delayed-service proxy caches bound methods separately from the service instance.
  // Update that cache too: assigning alone leaves native editor saves calling the cached original.
  Object.defineProperty(files, 'writeFile', { value: coordinatedWrite, configurable: true, writable: true });
  const writeText = textFiles.write.bind(textFiles);
  const coordinatedTextWrite: typeof textFiles.write = async (resource, value, options) => {
    if (resource.scheme !== 'vscode-remote' || resource.authority !== connection.authority || !resource.path.startsWith(connection.root + '/')) {
      return writeText(resource, value, options);
    }
    // The save's snapshot predates any typing while its encoded bytes are being written.
    const chunks: string[] = [];
    if (typeof value === 'string') chunks.push(value);
    else for (let chunk = value.read(); chunk !== null; chunk = value.read()) chunks.push(chunk);
    const text = chunks.join('');
    const result = await writeText(resource, text, options);
    saved.fire({ resource, text });
    return result;
  };
  textFiles.write = coordinatedTextWrite;
  Object.defineProperty(textFiles, 'write', { value: coordinatedTextWrite, configurable: true, writable: true });
  return { onDidSave: saved.event, isDirty: (resource: URI) => textFiles.isDirty(resource) };
}
