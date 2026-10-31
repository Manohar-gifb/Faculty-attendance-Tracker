// ESLint configuration for the existing frontend stack
// (ESLint 8 + eslint-plugin-react / react-hooks / react-refresh already in package.json).
// Tuned so the CURRENT codebase passes without modifying components or UI.
module.exports = {
  root: true,
  env: {
    browser: true,
    es2021: true,
    node: true,
  },
  extends: [
    'eslint:recommended',
    'plugin:react/recommended',
    'plugin:react-hooks/recommended',
  ],
  parserOptions: {
    ecmaVersion: 'latest',
    sourceType: 'module',
    ecmaFeatures: { jsx: true },
  },
  settings: {
    react: { version: 'detect' },
  },
  plugins: ['react', 'react-hooks', 'react-refresh'],
  ignorePatterns: ['dist/', 'node_modules/'],
  rules: {
    // The codebase doesn't use PropTypes (types come from backend DTOs)
    'react/prop-types': 'off',
    // Vite's JSX transform doesn't require `import React` in every file
    'react/react-in-jsx-scope': 'off',
    'react-hooks/exhaustive-deps': 'warn',
    // Context files export hooks alongside providers by design
    // (AuthContext.jsx) — react-refresh's only-export-components rule
    // would flag them; keep the plugin but disable that rule.
    'react-refresh/only-export-components': 'off',
    // Unused variables: allow args/vars caught by existing patterns but
    // still surface genuinely unused declarations as errors.
    'no-unused-vars': ['error', { args: 'none', caughtErrors: 'none', varsIgnorePattern: '^_' }],
    'no-console': 'off',
    'no-empty': ['error', { allowEmptyCatch: true }],
  },
  overrides: [
    {
      files: ['**/*.test.{js,jsx}', '**/test/**/*.{js,jsx}'],
      env: {
        jest: true,
      },
      globals: {
        vi: 'readonly',
        describe: 'readonly',
        it: 'readonly',
        test: 'readonly',
        expect: 'readonly',
        beforeEach: 'readonly',
        afterEach: 'readonly',
        beforeAll: 'readonly',
        afterAll: 'readonly',
      },
    },
  ],
};
