// Client-side validation copy (DESIGN.md §6). A message says what is wrong AND how to fix it; the
// form-level summary counts the invalid fields and links to each. Page-specific validators (CIDR,
// redirect-URI, name-required) live with the page/namespace that owns them.
export const validation = {
  oneFieldNeedsAttention: "1 field needs your attention",
  fieldsNeedAttention: "{{count}} fields need your attention",
} as const;
