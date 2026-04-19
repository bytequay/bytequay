import type { Bridge } from './types';

declare global {
  interface Window {
    bridge: Bridge;
  }
}

export {};
