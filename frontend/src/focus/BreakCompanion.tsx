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
import { useEffect, useRef, useState, type CSSProperties } from 'react';
import chubbyCatVideo from './assets/chubby-cat.webm';
import chubbyCatLeaveVideo from './assets/chubby-cat-leave.webm';
import chubbyCatPoster from './assets/chubby-cat-poster.png';
export const COMPANION_LEAVE_MS = 350;
export const CAT_ARRIVAL_SECONDS = 5.2;
export const CAT_REST_SECONDS = 15;
export const CAT_LEAVE_WALK_SECONDS = 5.5;
export const CAT_LEAVE_EDGE_SECONDS = 6.25;
export const CAT_LEAVE_SECONDS = 9;
export const CAT_VIDEO_STALL_MS = 5000;

type VideoPhase = 'loading' | 'entering' | 'waiting' | 'performing' | 'resting' | 'leaving' | 'fading' | 'still';

type PlaybackProps = {
  locked: boolean;
  exiting: boolean;
  onArrived: () => void;
  onExited: () => void;
};

function useReducedMotion() {
  const [reducedMotion, setReducedMotion] = useState(() => typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  useEffect(() => {
    const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
    const changed = () => setReducedMotion(preference.matches);
    preference.addEventListener('change', changed);
    return () => preference.removeEventListener('change', changed);
  }, []);
  return reducedMotion;
}

export function BreakCompanion({ locked, exiting, onArrived, onExited }: PlaybackProps) {
  const [phase, setPhase] = useState<VideoPhase>('loading');
  const [posterUnavailable, setPosterUnavailable] = useState(false);
  const [leaveReady, setLeaveReady] = useState(false);
  const reducedMotion = useReducedMotion();
  const videoRef = useRef<HTMLVideoElement>(null);
  const leaveVideoRef = useRef<HTMLVideoElement>(null);
  const actorRef = useRef<HTMLDivElement>(null);
  const current = useRef({ locked, exiting, onArrived, onExited, reducedMotion });
  current.current = { locked, exiting, onArrived, onExited, reducedMotion };
  const synchronize = useRef<() => void>(() => {});

  useEffect(() => {
    const mainVideo = videoRef.current, leaveVideo = leaveVideoRef.current;
    if (!mainVideo || !leaveVideo) return;
    let video = mainVideo;
    let live = true, arrived = false, exited = false, failed = false, playing = false, leaveDecoded = false, playAttempt = 0;
    let playbackPhase: VideoPhase = 'loading';
    let frame: number | undefined, watchdog: number | undefined, leaveTimer: number | undefined;
    const changePhase = (next: VideoPhase) => { playbackPhase = next; setPhase(next); };
    const notifyArrived = () => { if (!arrived) { arrived = true; current.current.onArrived(); } };
    const notifyExited = () => { if (!exited) { exited = true; current.current.onExited(); } };
    const setProgress = (progress: number) => actorRef.current?.style.setProperty('--cat-entry-progress', String(progress));
    const armWatchdog = () => {
      window.clearTimeout(watchdog);
      watchdog = window.setTimeout(fallback, CAT_VIDEO_STALL_MS);
    };
    const requestFrame = () => {
      const media = video, attempt = playAttempt;
      frame = media.requestVideoFrameCallback((now, metadata) => {
        if (media === video && attempt === playAttempt) decodedFrame(now, metadata);
      });
    };
    const setPlayback = (next: boolean) => {
      if (playing === next) return;
      playing = next;
      const attempt = ++playAttempt;
      if (next) {
        requestFrame();
        armWatchdog();
        void video.play().catch(() => { if (live && playing && attempt === playAttempt) fallback(); });
      }
      else {
        video.pause();
        if (frame !== undefined) video.cancelVideoFrameCallback(frame);
        frame = undefined;
        window.clearTimeout(watchdog);
      }
    };
    function fallback() {
      failed = true;
      setPlayback(false);
      if (!live) return;
      if (current.current.exiting) { notifyExited(); return; }
      if (document.hidden) return;
      setProgress(1);
      changePhase('still');
      notifyArrived();
    }
    const decodedFrame = (_now: number, metadata: VideoFrameCallbackMetadata) => {
      frame = undefined;
      if (!live || !playing) return;
      armWatchdog();
      if (video === leaveVideo) {
        if (!leaveDecoded) { leaveDecoded = true; setLeaveReady(true); }
        const progress = Math.max(0, Math.min((metadata.mediaTime - CAT_LEAVE_WALK_SECONDS) / (CAT_LEAVE_SECONDS - CAT_LEAVE_WALK_SECONDS), 1));
        const edgeProgress = Math.max(0, Math.min((metadata.mediaTime - CAT_LEAVE_WALK_SECONDS) / (CAT_LEAVE_EDGE_SECONDS - CAT_LEAVE_WALK_SECONDS), 1));
        actorRef.current?.style.setProperty('--cat-leave-progress', String(progress));
        actorRef.current?.style.setProperty('--cat-leave-edge-progress', String(edgeProgress));
      }
      else if (!arrived) {
        const progress = Math.min(metadata.mediaTime / CAT_ARRIVAL_SECONDS, 1);
        setProgress(progress);
        if (progress === 1) {
          setPlayback(false);
          changePhase('waiting');
          notifyArrived();
          synchronize.current();
          return;
        }
        if (playbackPhase === 'loading') changePhase('entering');
      }
      else if (playbackPhase === 'performing' && metadata.mediaTime >= CAT_REST_SECONDS) changePhase('resting');
      requestFrame();
    };
    const sync = () => {
      if (!live || exited) return;
      if (current.current.exiting) {
        if (!current.current.locked || current.current.reducedMotion || playbackPhase === 'still' || playbackPhase === 'loading') {
          setPlayback(false);
          notifyExited();
          return;
        }
        if (playbackPhase === 'resting') {
          setPlayback(false);
          video = leaveVideo;
          changePhase('leaving');
        }
        else if (playbackPhase !== 'leaving' && playbackPhase !== 'fading') {
          setPlayback(false);
          changePhase('fading');
          leaveTimer = window.setTimeout(notifyExited, COMPANION_LEAVE_MS);
        }
        if (playbackPhase === 'leaving') setPlayback(!document.hidden);
        return;
      }
      if (document.hidden) { setPlayback(false); return; }
      if (current.current.reducedMotion || failed) {
        setPlayback(false);
        setProgress(1);
        changePhase('still');
        notifyArrived();
        return;
      }
      if (playbackPhase === 'waiting' && current.current.locked) changePhase('performing');
      if (playbackPhase === 'loading' || playbackPhase === 'entering' || playbackPhase === 'performing' || playbackPhase === 'resting') setPlayback(true);
    };
    const ended = (event: Event) => {
      if (event.currentTarget !== video) return;
      if (video === leaveVideo) { setPlayback(false); notifyExited(); return; }
      if (!arrived) { fallback(); return; }
      setPlayback(false);
      if (current.current.exiting || failed) return;
      // The appended rest cycle shares its first/last pose; never replay the walk.
      video.currentTime = CAT_REST_SECONDS;
      changePhase('resting');
      sync();
    };
    const error = (event: Event) => { if (event.currentTarget === video) fallback(); };
    synchronize.current = sync;
    for (const media of [mainVideo, leaveVideo]) {
      media.addEventListener('error', error);
      media.addEventListener('ended', ended);
    }
    document.addEventListener('visibilitychange', sync);
    sync();
    return () => {
      live = false;
      setPlayback(false);
      window.clearTimeout(leaveTimer);
      synchronize.current = () => {};
      for (const media of [mainVideo, leaveVideo]) {
        media.removeEventListener('error', error);
        media.removeEventListener('ended', ended);
      }
      document.removeEventListener('visibilitychange', sync);
    };
  }, []);

  useEffect(() => { synchronize.current(); }, [locked, exiting, reducedMotion]);

  return <div className="break-companion" data-companion="chubby-cat" data-media="video" data-phase={phase} aria-hidden="true"
    style={{ '--companion-leave-time': `${COMPANION_LEAVE_MS}ms` } as CSSProperties}>
    <div ref={actorRef} className="break-companion__video-actor">
      <video ref={videoRef} className="break-companion__video" src={chubbyCatVideo} poster={chubbyCatPoster}
        hidden={phase === 'still' || leaveReady} muted playsInline preload={reducedMotion ? 'none' : 'auto'} disablePictureInPicture />
      <video ref={leaveVideoRef} className="break-companion__video break-companion__leave-video" src={chubbyCatLeaveVideo}
        data-ready={leaveReady} muted playsInline preload={reducedMotion ? 'none' : 'auto'} disablePictureInPicture />
      {phase === 'still' && (posterUnavailable ? <span className="break-companion__fallback">🐾</span>
        : <img className="break-companion__poster" src={chubbyCatPoster} alt="" draggable={false} onError={() => setPosterUnavailable(true)} />)}
    </div>
  </div>;
}
