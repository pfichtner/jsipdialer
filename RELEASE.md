# Release Process

This document describes how to cut a new release of jsipdialer. Releases are distributed as [GitHub Releases](https://github.com/pfichtner/jsipdialer/releases) and are built automatically by the [CI pipeline](.github/workflows/maven.yml) when a version tag is pushed.

## Versioning

The project uses Maven `major.minor.patch` versioning with `-SNAPSHOT` suffix for development versions:

```xml
<!-- pom.xml -->
<version>0.0.4-SNAPSHOT</version>
```

Released versions drop the `-SNAPSHOT` suffix (e.g., `0.0.4`), and the corresponding git tag uses a `v` prefix (e.g., `v0.0.4`).

## Overview

A release consists of three parts:

1. **Bump** the version in `pom.xml` from `X.Y.Z-SNAPSHOT` to `X.Y.Z`
2. **Tag** and push the tag — this triggers the CI build and GitHub Release creation
3. **Bump** the version to the next `X.Y.(Z+1)-SNAPSHOT` for continued development

---

## Option A: Manual release

### 1. Set the release version

```bash
# e.g., 0.0.4-SNAPSHOT → 0.0.4
./mvnw versions:set -DnewVersion=0.0.4 -DgenerateBackupPoms=false
```

This updates `pom.xml` in place.

### 2. Commit and tag

```bash
git add pom.xml
git commit -m "Release 0.0.4"
git tag v0.0.4
```

### 3. Push the tag

```bash
git push origin v0.0.4
```

Pushing the tag triggers the [CI pipeline](.github/workflows/maven.yml):

- Builds the fat JAR and GraalVM native image
- Runs unit and integration tests
- Compresses the native binary with UPX
- Packages artifacts into `jsipdialer_v0.0.4_linux.tar.gz`
- Creates a GitHub Release with the artifacts attached

### 4. Bump to the next SNAPSHOT

```bash
./mvnw versions:set -DnewVersion=0.0.5-SNAPSHOT -DgenerateBackupPoms=false
git add pom.xml
git commit -m "Prepare next development version 0.0.5-SNAPSHOT"
git push origin <branch>
```

---

## Option B: Using `mvn release:prepare`

The `maven-release-plugin` can automate the version bump, commit, tag, and next-snapshot bump in a single step:

```bash
./mvnw release:prepare \
  -DtagNameFormat=v@{project.version} \
  -DscmCommentPrefix="[release] "
```

This will:

1. Check that no dependencies are still on `SNAPSHOT` versions
2. Set the release version in `pom.xml` and commit
3. Create and push the `v0.0.4` tag (triggers CI → GitHub Release)
4. Bump to the next `SNAPSHOT` version in `pom.xml` and commit
5. Push both commits and the tag to the remote

The `-DtagNameFormat=v@{project.version}` flag is required because the project uses a `v` prefix for tags (e.g., `v0.0.4`); the plugin defaults to `artifactId-version` (e.g., `jsipdialer-0.0.4`).

### Why `mvn release:perform` is not used

`mvn release:perform` checks out the release tag and runs `mvn deploy` to publish artifacts to a Maven repository. This project does **not** use `mvn deploy` — it has no `<distributionManagement>` section in `pom.xml` because releases are built and published entirely through GitHub Actions. The CI pipeline triggered by the tag push handles building, testing, packaging, and creating the GitHub Release. Therefore, `release:perform` is not needed and will fail if invoked.

### Dry run

To preview what `release:prepare` would do without making any changes:

```bash
./mvnw release:prepare \
  -DtagNameFormat=v@{project.version} \
  -DdryRun=true
```

---

## Verifying a release

After the tag is pushed:

1. Check the [Actions tab](https://github.com/pfichtner/jsipdialer/actions) — the "GraalVM Native Image builds" workflow should run the `build` and `release` jobs
2. Check the [Releases page](https://github.com/pfichtner/jsipdialer/releases) — a new release with the `jsipdialer_v0.0.4_linux.tar.gz` artifact should appear
3. Verify the tarball contains both the native binary (`jsipdialer`) and the fat JAR (`jsipdialer-0.0.4.jar`)

## Troubleshooting

| Problem | Solution |
|---------|----------|
| CI builds but no GitHub Release is created | Ensure the tag starts with `v` and was pushed (the `release` job only runs for `refs/tags/*`) |
| `release:prepare` fails on SNAPSHOT dependencies | Wait for Dependabot or manually update any `SNAPSHOT` dependencies to release versions |
| Tag pushed but wrong artifact name | The tarball name is derived from the tag name; ensure the tag is `v0.0.4` (not `0.0.4` or `jsipdialer-0.0.4`) |