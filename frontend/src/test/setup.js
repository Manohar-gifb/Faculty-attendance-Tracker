import '@testing-library/jest-dom';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// Runs cleanup after each test case (e.g. clearing jsdom)
afterEach(() => {
  cleanup();
  localStorage.clear();
  sessionStorage.clear();
});
