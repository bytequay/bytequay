// Minimal ambient module declaration so TypeScript knows that importing an
// SVG/PNG through Vite resolves to a URL string. Vite handles the actual
// bundling at build time.
declare module '*.svg' {
  const url: string;
  export default url;
}

declare module '*.png' {
  const url: string;
  export default url;
}
