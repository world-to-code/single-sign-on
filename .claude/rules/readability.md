---
paths:
  - "sso-backend/**/*.java"
  - "sso-frontend/**/*.ts"
  - "sso-frontend/**/*.tsx"
---

# Readability — code is read far more often than it is written

This is a production Identity Provider. Every method here is something a reviewer has to be able to hold in
their head while asking "can this be abused?" — a method nobody can follow is a method nobody can audit.
Cleverness that costs a reader thirty seconds has bought nothing and sold the review.

Robert C. Martin's *Clean Code* is the reference; the rules below are the parts of it this codebase keeps
getting wrong, stated as things to check.

## One level of abstraction per method

A method should read as a sequence of statements at the SAME conceptual level. Mixing "decide the policy" with
"iterate a map of lists and filter blanks" in one body forces the reader to change altitude mid-sentence.

When a method does step A, then step B, then step C, and each needs its own paragraph of explanation — those
are three methods with names, not three commented blocks.

## Never nest a lambda inside a lambda

The specific shape this repo keeps producing:

```java
// DON'T — three levels, no name on any of them, and the reader must hold all three to see the effect
values.forEach((key, list) -> list.stream()
        .filter(v -> v != null && !v.isBlank())
        .forEach(v -> attributes.add(EntityKind.USER, id, key, v)));
```

```java
// DO — the loop says what it iterates, the helper says what it does
for (Map.Entry<String, List<String>> attribute : values.entrySet()) {
    writeNonBlankValues(userId, attribute.getKey(), attribute.getValue());
}
```

Rules:
- **A lambda body containing another lambda that itself has a body is out.** One `.stream()...collect(...)`
  chain is fine; a `forEach` whose body is a `forEach` is not.
- A stream chain longer than **three operations** wants a named method, or a `for` loop.
- Prefer a plain `for` when the operation is a side effect. `forEach` on a stream reads as a transformation
  and then performs a write, which is the mismatch that makes these hard to scan.
- Never write a lambda parameter as a single letter when the body is more than one expression. `v`, `e`, `x`
  tell the reader nothing; `value`, `entry`, `candidate` cost four characters.

## Comment WHY, and comment when the code cannot say it

The house style is already comment-rich, and that is deliberate. What it is not is decorative:

- **Explain the reasoning, the trade-off, the attack, the incident** — the things the code cannot state.
  "Refuse before creating: a required attribute missing should not leave a half-made account behind."
- **Keep an inline comment to two lines.** Past that it stops being a signpost and becomes something else to
  read before you reach the code — the opposite of the point. If the reasoning genuinely needs a paragraph, it
  belongs in the method or class Javadoc, where a reader chooses when to read it; the inline note then points
  at it in one line.
- **A long or dense method needs interior comments** marking its phases, so a reader can skim the shape before
  reading the detail. One line per phase. If a method is over ~20 lines and needs more than that, it is asking
  to be split.
- **Do not narrate the syntax.** `// loop over the rows` above a `for` over rows is noise that will rot.
- A comment that explains WHAT a badly-named thing does is a rename waiting to happen. Fix the name first;
  keep the comment only if it says something the name cannot.
- **A comment that is no longer true is worse than none** — it actively misleads a reviewer. This has already
  happened here (a class claiming an inbound guard existed before the inbound path did). When you change
  behaviour, re-read the comments around it.

## Method size and shape

- **Small.** A method that does not fit on a screen is one you cannot check for a missing branch.
- **Few arguments.** Three is a lot; four is a smell the rules already name
  ([constructors-factories](backend/constructors-factories.md)). Two adjacent parameters of the same type are a
  swap the compiler cannot catch — make one a named type.
- **No boolean/enum flag parameters that select behaviour.** `create(user, SUPPRESS)` reads as a mode switch;
  a named factory (`NewUserCommand.fromImport(...)`) says which caller you are and why.
- **No output parameters.** A method that mutates its argument to communicate is one whose signature lies.
- **Command/query separation.** A method either does something or answers something. One that does both makes
  every call site ambiguous about whether order matters.

## Names

- Intention-revealing, pronounceable, searchable. `usableIds` over `filtered`; `unusableGroups` over `bad`.
- **Say what it is, not what it is made of.** `AttributeSourceAuthors`, not `UuidSetWrapper`.
- **One word per concept.** If the codebase says `refuse`, do not introduce `reject` beside it for the same act.
- A boolean reads as a claim: `governsUsers()`, `administersWholeTier()` — not `checkTier()`.

## Nesting and control flow

- **Guard clauses over nesting.** Return or throw on the exceptional case first; keep the happy path flat and
  at one indentation level.
- Two levels of indentation inside a method body is usually the limit before the inner part wants a name.
- **No `else` after a `return`.**

## Duplication

Two copies of a rule are two chances for one to drift, and in this codebase drift means a check that holds on
one path and not another — which is how a bulk route became a way around a per-screen check. When you find the
same decision expressed twice, the fix is to move the decision, not to keep them in step by hand.

## What to check before calling a change done

- Can somebody who has never seen this file follow the method top to bottom without scrolling back?
- Is there a lambda inside a lambda?
- Does every comment say something the code cannot — in two lines or fewer?
- Is any comment now false because of this change?
- Does the longest method have interior comments marking its phases — or should it be two methods?

Related: [constructors-factories](backend/constructors-factories.md), [file-layout](backend/file-layout.md),
[immutability](backend/immutability.md); reviewers: `.claude/agents/god-class-reviewer.md`,
`.claude/agents/solid-reviewer.md`.
