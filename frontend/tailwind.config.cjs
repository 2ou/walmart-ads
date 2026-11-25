const { heroui } = require('@heroui/react');

/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    './src/**/*.{js,ts,jsx,tsx}',
    './node_modules/@heroui/theme/dist/**/*.{js,ts,jsx,tsx}',
    './node_modules/@heroui/react/node_modules/@heroui/theme/dist/**/*.{js,ts,jsx,tsx}',
  ],
  corePlugins: {
    // Preserve legacy HTML/CSS element styles inside index.html without Tailwind reset
    preflight: false,
  },
  theme: {
    extend: {
      fontFamily: {
        sans: [
          '-apple-system',
          'BlinkMacSystemFont',
          '"SF Pro Display"',
          '"SF Pro Text"',
          '"PingFang SC"',
          '"Helvetica Neue"',
          'system-ui',
          'sans-serif',
        ],
        mono: [
          '"SF Mono"',
          '"SFMono-Regular"',
          '"Fira Code"',
          '"Cascadia Mono"',
          'Menlo',
          'Consolas',
          'monospace',
        ],
      },
    },
  },
  darkMode: 'class',
  plugins: [
    heroui({
      defaultTheme: 'light',
      layout: {
        radius: {
          small: '10px',
          medium: '14px',
          large: '16px',
        },
        borderWidth: {
          small: '1px',
          medium: '1px',
          large: '1.5px',
        },
      },
      themes: {
        light: {
          colors: {
            background: '#f5f5f7',
            foreground: '#1d1d1f',
            primary: {
              DEFAULT: '#0071e3',
              foreground: '#ffffff',
            },
            success: {
              DEFAULT: '#248a3d',
              foreground: '#ffffff',
            },
            warning: {
              DEFAULT: '#ff9500',
              foreground: '#ffffff',
            },
            danger: {
              DEFAULT: '#ff3b30',
              foreground: '#ffffff',
            },
            focus: '#0071e3',
          },
        },
      },
    }),
  ],
};
