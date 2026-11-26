# Pilot and future public product

## Repositories

| Workstream | Public source repository | Current priority |
| --- | --- | --- |
| Personal pilot | [dose-companion-pilot](https://github.com/mohamadmustaphaelhajj/dose-companion-pilot) | Active: complete this first |
| Future public product | [dose-companion](https://github.com/mohamadmustaphaelhajj/dose-companion) | Deferred until the pilot is ready |

“Public repository” describes source-code visibility. It does not make the pilot's backend publicly usable without authentication. API keys, pairing tokens, real prescription images and personal journals must remain outside both repositories.

The pilot was seeded from the already-reviewed `b9d89da` milestone (guided scanning, recovery and workflow validation), retaining Git history. The existing public-product repository and its main branch were preserved. The two repositories start from shared ancestry, so portable fixes can later be transferred with reviewed cherry-picks or a targeted patch rather than maintaining unrelated copies.

## This workspace

- `origin`: `https://github.com/mohamadmustaphaelhajj/dose-companion-pilot.git` — active pilot target.
- `public`: `https://github.com/mohamadmustaphaelhajj/dose-companion.git` — future public-product target.
- Active work continues on `feature/independent-phone-use`, based on `b9d89da`.
- Existing local `main` continues to track `public/main`; do not assume it is pilot main. Fetch and explicitly choose the correct remote branch before switching or integrating work.

Use explicit remote/branch names for publication. Never mirror-push, push all branches, or automatically merge pilot work into the future product. Keep editor settings and generated output excluded from milestone commits. Ask the owner before each new commit, as before.

## Pilot scope next

Complete independent phone use: hosted HTTPS label reading, server-held OpenAI credentials, protected phone pairing, actionable readiness checks, and continued offline reminders/history. Prepare an external warm-up schedule around the pilot's agreed morning/night windows using the `America/Toronto` time zone. Scheduling values belong in deployment configuration; the intended interval is five minutes with a short lead-in before each window.

The Render approach and warm-up schedule are agreed design directions, not deployed functionality. A hosting account, remote scheduler and final deployment configuration are still needed. The Mac/USB bridge remains the working connection until a hosted service is deployed and tested from the phone without USB.

Do not build public subscriptions, public onboarding, multi-user accounts, or a paid public backend in this milestone. A future paid backend is a separate product/deployment decision.

## Shared changes and commits

Build meaningful increments on the pilot, verify them and obtain approval before committing. Separate reusable fixes from pilot-only hosting/schedule configuration when practical. Later bring useful, validated shared fixes into the public product deliberately, with an explanation of their benefit and appropriate tests. Schedule commits around reviewable milestones in each repository.

Git authorship in this checkout uses the owner's GitHub noreply address for future commits. Existing commits were not amended. Contribution-graph credit also depends on [GitHub's branch and attribution rules](https://docs.github.com/en/account-and-profile/reference/profile-contributions-reference); a push to a feature branch alone should not be promised as profile activity. Focus on real milestones and reviewed integration into each repository's default branch.
