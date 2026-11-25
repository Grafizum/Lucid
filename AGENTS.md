# Working on this repository

This repository is owned by **grafizum** (the company account) and edited from the
private **MatrixZockt** account. `main` is protected, and every change goes through a
pull request that grafizum approves.

## The flow

1. Branch off `main`: `git switch -c <fix|feat|docs|chore>/<topic>`.
2. Commit as MatrixZockt with a message that says **why** the change is made.
3. Push the branch: `git push -u origin <branch>`.
4. Open the pull request: `gh pr create --base main --head <branch> --title "…" --body "…"`.
   The body states the change, the reason, and what was actually checked — never a check
   that did not run.
5. Review as the owner: `gh auth switch --hostname github.com --user grafizum`, read the
   diff with `gh pr diff`, then `gh pr review <number> --approve`.
6. Merge as the owner: `gh pr merge <number> --squash --delete-branch`.
7. Switch back: `gh auth switch --hostname github.com --user MatrixZockt`.

## What the branch protection means

- A direct push to `main` is rejected: *changes must be made through a pull request*.
- One approving review is required, and a new push dismisses the previous approval.
- Merge commits are not allowed, so history stays linear (squash or rebase).
- Force pushes to `main` and deleting `main` are blocked.
- Admin enforcement is off, so grafizum can still push a hotfix directly — only when the
  owner explicitly asks for one.

## Version

`version.properties` at the top of the repository is the only place the version is
written down. `android/app/build.gradle.kts` reads both numbers from it, and
`VersionSourceTest` fails if a second copy ever appears.

- **One version name per release**, moved on the release build itself, by the step the
  work deserves:

  | Step | What it is | 1.0.1 becomes |
  | --- | --- | --- |
  | `fix` | a bug fixed, a small change | 1.0.2 |
  | `feature` | something people can do that they could not before | 1.1.1 |
  | `breaking` | a remake, or a release that changes how the app is used | 2.0.0 |

  The step comes from `lucid.releaseType` in `android/gradle.properties` when it is set —
  which is what a real release should do — and otherwise from the commits since the last
  bump: a `BREAKING` note or a `type!:` subject is a remake, a `feat:` subject or a new
  file under `android/app/src/main` is a feature, anything else that touches the app is a
  fix, and documentation or store-listing work on its own earns no name at all.
  `./gradlew bumpVersion -Ptype=fix|feature|breaking -Pnote="what shipped"` does the same
  thing by hand, without building, and prints `old -> new`.
- **One version code per signed build.** `bundleRelease` (what Android Studio's
  "Generate Signed App Bundle" runs) and `assembleRelease` raise `VERSION_CODE` by one
  before the artifact is built, so every upload to Play carries a code nobody has used.
  Intermediate work such as `compileReleaseKotlin` and `testReleaseUnitTest` does not
  touch it, and neither does a debug build.
- **One name per commit.** A release build records the commit it named in
  `android/.gradle/lucid-version-state`, so building the same release twice does not walk
  the name forward twice; commit the release, do more work, and the next build moves it
  again. Debug builds never touch either number.
- **A dry run costs nothing.** `./gradlew :app:bundleRelease --dry-run` inspects the
  build without writing to `version.properties`, so a release can be checked for free.

Do not hand-edit either number, and do not bump them inside a feature PR — the release is
the only thing that moves them.

## Signing

Release signing reads `LUCID_KEYSTORE_PATH`, `LUCID_KEYSTORE_PASSWORD`, `LUCID_KEY_ALIAS`
and `LUCID_KEY_PASSWORD` from the environment or from `.env` at the repository root. The
keystore itself stays outside the repository, in `~/.grafizum/keys/lucid-upload.jks`, and
both it and `.env` are git-ignored. Without the four variables a release build still runs,
just unsigned.

## Rules

- Do not disable or bypass branch protection to land a change.
- Do not approve unverified work; if a change is untested, open the PR and say so.
- One topic per pull request.
- Never change visibility, collaborators, secrets or repository ownership on your own.
