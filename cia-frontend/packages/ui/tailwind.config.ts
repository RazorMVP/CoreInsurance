import type { Config } from 'tailwindcss';

export default {
  darkMode: ['class'],
  content: ['./src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        background:  'var(--background)',
        foreground:  'var(--foreground)',
        border:      'var(--border)',
        input:       'var(--input)',
        ring:        'var(--ring)',
        primary: {
          DEFAULT:    'var(--primary)',
          foreground: 'var(--primary-foreground)',
        },
        secondary: {
          DEFAULT:    'var(--secondary)',
          foreground: 'var(--secondary-foreground)',
        },
        muted: {
          DEFAULT:    'var(--muted)',
          foreground: 'var(--muted-foreground)',
        },
        accent: {
          DEFAULT:    'var(--accent)',
          foreground: 'var(--accent-foreground)',
        },
        destructive: {
          DEFAULT:    'var(--destructive)',
          foreground: 'var(--destructive-foreground)',
        },
        card: {
          DEFAULT:    'var(--card)',
          foreground: 'var(--card-foreground)',
        },
        popover: {
          DEFAULT:    'var(--popover)',
          foreground: 'var(--popover-foreground)',
        },
        /* Nubeero brand palette as Tailwind utilities */
        teal: {
          50:  'var(--cia-teal-50)',
          100: 'var(--cia-teal-100)',
          200: 'var(--cia-teal-200)',
          300: 'var(--cia-teal-300)',
          400: 'var(--cia-teal-400)',
          500: 'var(--cia-teal-500)',
          600: 'var(--cia-teal-600)',
          700: 'var(--cia-teal-700)',
          800: 'var(--cia-teal-800)',
          900: 'var(--cia-teal-900)',
        },
        charcoal: {
          50:  'var(--cia-charcoal-50)',
          100: 'var(--cia-charcoal-100)',
          200: 'var(--cia-charcoal-200)',
          300: 'var(--cia-charcoal-300)',
          400: 'var(--cia-charcoal-400)',
          500: 'var(--cia-charcoal-500)',
          600: 'var(--cia-charcoal-600)',
          700: 'var(--cia-charcoal-700)',
          800: 'var(--cia-charcoal-800)',
          900: 'var(--cia-charcoal-900)',
        },
      },
      fontFamily: {
        display: ['var(--font-display)'],
        body:    ['var(--font-body)'],
        sans:    ['var(--font-body)'],
      },
      borderRadius: {
        lg: 'calc(var(--radius) + 2px)',
        md: 'var(--radius)',
        sm: 'calc(var(--radius) - 2px)',
      },
      keyframes: {
        'fade-in': {
          from: { opacity: '0', transform: 'translateY(4px)' },
          to:   { opacity: '1', transform: 'translateY(0)' },
        },
        'slide-in-left': {
          from: { opacity: '0', transform: 'translateX(-8px)' },
          to:   { opacity: '1', transform: 'translateX(0)' },
        },
      },
      animation: {
        'fade-in':      'fade-in 0.18s cubic-bezier(0.16, 1, 0.3, 1)',
        'slide-in-left':'slide-in-left 0.2s cubic-bezier(0.16, 1, 0.3, 1)',
      },
    },
  },
  plugins: [],
} satisfies Config;
