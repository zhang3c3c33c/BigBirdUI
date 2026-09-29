import { defineConfig } from 'vite';

export default defineConfig({
  base: './',
  build: {
    ...(process.env.BBUI_DESKTOP_BUILD ? { outDir: '../../.desktop/app/ui', emptyOutDir: true } : {}),
    target: 'es2020', sourcemap: false,
    rollupOptions: {
      ...(process.env.BBUI_DESKTOP_BUILD ? { input: ['desktop.html', 'stop.html'] } : {}),
      onwarn(warning, warn) {
        if (warning.code === 'MODULE_LEVEL_DIRECTIVE' && warning.message.includes('use client')) return;
        warn(warning);
      },
    },
  },
});
