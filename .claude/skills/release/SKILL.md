---
name: release
description: Release a new KonanPlugin version — bump, changelog, publish to the Gradle Plugin Portal, verify, tag, and create the GitHub release. Use when asked to release, publish, or cut a version (e.g. "publish alpha07", "release 2.0.0").
---

# Releasing KonanPlugin

A release is: version bump commit on `main` → `publishPlugins` → verify from the Portal → tag → GitHub release.
Portal versions are **immutable**: a published version can never be fixed or republished, only superseded.
So every check happens *before* `publishPlugins`, and nothing after it can be retried with the same version.

## 0. Preconditions

- Everything to ship is merged into `main`; the release is cut from `main`, never a feature branch.
  `git checkout main && git pull --ff-only`, and the working tree is clean.
- CI on `main`'s head is green: `gh run list --branch main -L 3`.
- Pick the version: the next after the last tag (`git tag --sort=-creatordate | head -1`). While on 2.0
  alphas: `2.0.0-alphaNN`, one higher. Never reuse a number, even one whose publish failed half-way.
- Credentials (`gradle.publish.key`, `gradle.publish.secret`) live in `~/.gradle/gradle.properties`.
  **Never print, cat, grep or echo them**, and never pass them on a command line.

## 1. Bump and changelog (one commit on `main`)

- `git grep -n "<previous version>" -- ':!CHANGELOG.md'` — today `build.gradle.kts` (`version = …`) and the
  README's `plugins { }` snippet. Replace each with the new version.
- Add a `## <version>` section at the top of `CHANGELOG.md`, above the previous one, under `### Fixed`,
  `### Changed` or `### Added`. Collect what shipped from `git log <previous tag>..main --oneline` and the
  merged PRs/issues. Match the existing entries: a bold one-line summary linking the issue or PR, then what
  broke and for whom, what it does now, and how to get the old behavior if any. Mark breaking changes
  with `**Breaking:**` and what users must change.
- Commit: `chore: version <version>` with the `Co-Authored-By` trailer. Push only after step 3 succeeds
  (keeps `main` from announcing a version that failed to publish).

## 2. Publish

```bash
./gradlew publishPlugins
```

`checkVersionUnpublished` runs first and fails if the version is already on the Portal — that means the
bump was skipped or the number reused; bump again, never work around it. Do **not** also run
`publishToMavenLocal` for a released version: a local copy shadows the Portal's for consumers with
`mavenLocal()` first (#8). Local test builds use a `-SNAPSHOT` version instead.

## 3. Verify from the Portal only

"Works locally" proves nothing. Configure a throwaway project that can only reach the Portal:

```bash
dir=$(mktemp -d)   # or the session scratchpad
echo 'plugins { id("io.github.lemcoder.konanplugin") version "<version>" }' > "$dir/build.gradle.kts"
touch "$dir/settings.gradle.kts"
./gradlew -q -p "$dir" --refresh-dependencies buildEnvironment | grep konan   # resolves <version>
./gradlew -q -p "$dir" tasks --group konan                                    # konanConfig is listed
```

The Portal can take a few minutes to serve a new version; retry rather than republish.

## 4. Push, tag, release

```bash
git push origin main
git tag -a v<version> -m "<version>"
git push origin v<version>
```

Release notes are the version's CHANGELOG section followed by the `plugins { }` snippet:

````
<the CHANGELOG section's body, without its "## <version>" heading>

```kotlin
plugins {
    id("io.github.lemcoder.konanplugin") version "<version>"
}
```
````

```bash
gh release create v<version> --title <version> --notes-file <notes.md> --prerelease   # --prerelease while alpha/beta/rc
```

## 5. Close out

- Issues fixed by the release: if a PR's `Fixes #N` already closed one, leave a comment naming the version
  that ships the fix only if the reporter is waiting on it; ask before closing any still-open issue.
- Report to the user: the Portal URL (`https://plugins.gradle.org/plugin/io.github.lemcoder.konanplugin`),
  the release URL, and what the Portal-only check resolved.
