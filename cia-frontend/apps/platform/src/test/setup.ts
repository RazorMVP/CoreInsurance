import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

// With `globals: false` in vitest.config.ts, @testing-library/react does NOT
// auto-register its afterEach(cleanup) (auto-cleanup hooks off the global
// afterEach). Without this, the rendered DOM leaks between tests in the same
// file — a render in test A is still present when test B queries the document.
afterEach(cleanup);
