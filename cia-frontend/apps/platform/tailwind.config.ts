import type { Config } from 'tailwindcss';
import baseConfig from '../../packages/ui/tailwind.config';

export default {
  ...baseConfig,
  content: [
    './index.html',
    './src/**/*.{ts,tsx}',
    '../../packages/ui/src/**/*.{ts,tsx}',
  ],
} satisfies Config;
