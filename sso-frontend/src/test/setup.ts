import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";

// jsdom implements neither of these, and the auth shell reaches for both on first paint: the theme toggle
// resolves the OS colour scheme, and the branding loader applies an accent. Stub them once, globally, so a
// component test fails on the behaviour under test rather than on the environment.
if (!window.matchMedia) {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }));
}

// Vitest globals are off, so Testing Library's auto-cleanup does not register itself — unmount explicitly or
// a previous test's DOM leaks into the next one's queries.
afterEach(cleanup);
