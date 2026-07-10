import type { en } from "./en";

// Declaration-merge the English bundle as the resource shape. This makes `tsc` the build-time gate:
// an unknown key in t(...) is a type error, and a `ko` bundle missing or adding a key fails to compile
// (each ko namespace is typed `typeof en<NS>`) — DESIGN.md §10's byte-identical key sets, enforced.
declare module "i18next" {
  interface CustomTypeOptions {
    defaultNS: "common";
    resources: typeof en;
  }
}
