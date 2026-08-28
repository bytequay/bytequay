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
import { type RefObject, useEffect, useRef, useState } from 'react';

type BetterWordsButtonProps = {
  text: string;
  textareaRef: RefObject<HTMLTextAreaElement | null>;
  repository: string;
  workspaceId?: string;
  disabled?: boolean;
  onError?: (failure: unknown | null) => void;
};

export function replaceTextareaText(textarea: HTMLTextAreaElement, text: string) {
  const normalized = normalizeTextareaText(text);
  if (textarea.value === normalized) return true;
  textarea.focus();
  textarea.setSelectionRange(0, textarea.value.length);
  try {
    textarea.ownerDocument.execCommand('insertText', false, normalized);
  }
  catch {
    return false;
  }
  return textarea.value === normalized;
}

export function normalizeTextareaText(text: string) {
  return text.replace(/\r\n?/g, '\n');
}

export function isCurrentBetterWordsResponse(
  request: number,
  activeRequest: number,
  requestedText: string,
  currentText: string,
) {
  return request === activeRequest && requestedText === currentText;
}

export default function BetterWordsButton({
  text,
  textareaRef,
  repository,
  workspaceId,
  disabled = false,
  onError,
}: BetterWordsButtonProps) {
  const [improving, setImproving] = useState(false);
  const [original, setOriginal] = useState<string | null>(null);
  const [replacement, setReplacement] = useState<string | null>(null);
  const activeRequest = useRef(0);
  const currentText = useRef(text);
  const currentRepository = useRef(repository);
  const currentWorkspaceId = useRef(workspaceId);
  const currentlyDisabled = useRef(disabled);
  const replacementSeen = useRef(false);
  currentText.current = text;
  currentRepository.current = repository;
  currentWorkspaceId.current = workspaceId;
  currentlyDisabled.current = disabled;

  useEffect(() => () => {
    activeRequest.current += 1;
  }, []);

  useEffect(() => {
    if (!disabled || !improving) return;
    activeRequest.current += 1;
    setImproving(false);
  }, [disabled, improving]);

  useEffect(() => {
    if (original === null || replacement === null) return;
    if (text === replacement) {
      replacementSeen.current = true;
    }
    else if (replacementSeen.current && text === original) {
      replacementSeen.current = false;
      setOriginal(null);
      setReplacement(null);
    }
  }, [original, replacement, text]);

  const improve = async () => {
    const requestedText = text;
    const requestedTextarea = textareaRef.current;
    if (!requestedText.trim() || requestedTextarea === null) return;
    const request = ++activeRequest.current;
    setImproving(true);
    onError?.(null);
    try {
      const improved = await window.bridge.betterWords(requestedText, repository, workspaceId);
      if (!isCurrentBetterWordsResponse(
        request, activeRequest.current, requestedText, currentText.current)
        || repository !== currentRepository.current
        || workspaceId !== currentWorkspaceId.current || currentlyDisabled.current) return;
      if (!improved.trim()) throw new Error('The agent returned no improved text.');
      const replacementText = normalizeTextareaText(improved);
      if (replacementText === normalizeTextareaText(requestedText)) return;
      const textarea = textareaRef.current;
      if (textarea !== requestedTextarea || textarea.value !== normalizeTextareaText(requestedText)) return;
      if (!replaceTextareaText(textarea, replacementText)) {
        throw new Error('Could not replace the comment text.');
      }
      replacementSeen.current = false;
      setOriginal(requestedText);
      setReplacement(replacementText);
    }
    catch (failure) {
      const textarea = textareaRef.current;
      if (isCurrentBetterWordsResponse(
        request, activeRequest.current, requestedText, currentText.current)
        && repository === currentRepository.current && workspaceId === currentWorkspaceId.current
        && !currentlyDisabled.current
        && textarea === requestedTextarea && textarea.value === normalizeTextareaText(requestedText)) {
        onError?.(failure);
      }
    }
    finally {
      if (request === activeRequest.current) setImproving(false);
    }
  };

  const discard = () => {
    if (original === null) return;
    onError?.(null);
    const textarea = textareaRef.current;
    if (textarea === null || !replaceTextareaText(textarea, original)) {
      onError?.(new Error('Could not restore the original comment.'));
      return;
    }
    activeRequest.current += 1;
    replacementSeen.current = false;
    setOriginal(null);
    setReplacement(null);
  };

  return (
    <button className={`pr-better-words${original === null ? '' : ' is-discard'}`} type="button"
      disabled={disabled || improving || (original === null && !text.trim())}
      aria-busy={improving}
      onClick={() => { if (original === null) void improve(); else discard(); }}>
      {improving ? 'Improving…' : original === null ? 'Better words' : 'Discard'}
    </button>
  );
}
