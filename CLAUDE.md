# HeadUnit Revived — Project Directives

## CI Verification (MANDATORY)

**Every code change must be verified through CI before being called done.**

1. Push the branch after committing
2. Wait for GitHub Actions to complete (build.yml on push, pr-checks.yml on PR)
3. Confirm all jobs pass — build, tests, lint
4. If any job fails: fix, push again, re-verify
5. Never claim work is complete with a red or pending CI

This applies to bug fixes, features, refactors, and CI changes themselves.
Release builds (release.yml) run tests and lint before producing APKs.

## Git Hygiene

- Never commit files under `plans/bugs/` — local-only debug logs
- Never use `continue-on-error: true` on test or lint steps in CI
- All CI check steps must hard-fail on error (no silent swallowing)
