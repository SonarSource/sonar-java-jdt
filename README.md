<!-- Sonar Marketing hosts these approved brand assets on its Kentico Kontent CDN (assets-eu-01.kc-usercontent.com). Shared URLs are intentional; consult Marketing before replacing them. -->
<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://assets-eu-01.kc-usercontent.com/ef593040-b591-0198-9506-ed88b30bc023/a23fc7ba-23f0-489a-829d-ed88c0748521/Sonar_Logo_Dark%20Backgrounds.svg">
    <img src="https://assets-eu-01.kc-usercontent.com/ef593040-b591-0198-9506-ed88b30bc023/82c13eba-d95c-4bb8-8007-7ce77c14e043/Sonar_Logo_Light%20Backgrounds.svg" alt="Sonar logo" width="400">
  </picture>
</p>

[![Build](https://github.com/SonarSource/sonar-java-jdt/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/SonarSource/sonar-java-jdt/actions/workflows/build.yml)

<!-- sonar-marketing:start -->
<!-- Marketing maintains this section. For wording changes, consult the relevant Product Marketing Manager (PMM). Repository CODEOWNERS review accuracy and merge changes. -->

# Packaging Eclipse's Java Development Tools for SonarJava

This repository packages Eclipse's Java Development Tools (JDT) for SonarJava, including patches needed by the Java analyzer. The maintenance instructions below explain how to update JDT and test the resulting package with SonarJava.

To learn more about Sonar’s Java analysis, visit the [Sonar website](https://www.sonarsource.com/knowledge/languages/java/).

<!-- sonar-marketing:end -->

This repository exists because we need to patch some files from eclipse JDT to fix some issues, but we can only do this in a repository with the same license as Eclipse.
When a new version of JDT is released, we update the dependency and reapply the complete ordered patch series in `patches/series`.

## License

`Eclipse Public License - v 2.0` see [LICENSE](LICENSE).

## Maintenance tool

[scripts/patchECJ.py](scripts/patchECJ.py) is the command-line tool for maintaining SonarJava's changes to Eclipse JDT.
It keeps the upstream dependency, the patches, and the patched Java sources consistent when upgrading JDT or changing a fix.

Most of JDT is packaged directly from Maven dependencies. This repository also contains complete copies of a few upstream classes:
`ASTParser`, `ProblemHandler`, and `ProblemReporter`. Maven compiles these local copies and includes them as replacements for the
original classes in `jdt-package`. Each copy contains the upstream implementation plus small changes needed by SonarJava.
Updating only the dependency version would leave these replacements based on an older implementation; replacing them with pristine
upstream sources would discard the SonarJava fixes. The tool updates both parts together by applying the complete patch series to
the new upstream sources.

The files involved have distinct roles:

| File or directory | Role |
| --- | --- |
| [pom.xml](pom.xml) | Selects the JDT Core dependency that Maven packages. |
| [patches/upstream.json](patches/upstream.json) | Records the upstream baseline: JDT Core and ECJ versions, SHA-256 checksums of their POMs and source JARs, and the source files to replace. |
| [patches/series](patches/series) | Lists every active patch in application order, including patches that depend on earlier ones. |
| `patches/*.patch` | Describe the individual SonarJava changes to upstream code, grouped by purpose and independent of Git commit history. |
| [src/main/java/org/eclipse/jdt/](src/main/java/org/eclipse/jdt/) | Contains the patched replacement classes that Maven compiles, together with separately maintained local helpers. |

For the replacement classes listed in the manifest, the tool enforces this relationship:

```text
Published upstream sources + patches applied in series order = checked-in replacement sources
```

The checked-in replacement sources make ordinary Maven builds and IDE use straightforward. The patches record how to recreate those
sources and carry the fixes forward to another release. A reconstruction check compares the recreated files byte for byte with the
checked-in copies, catching changes that were made on only one side. Local helpers such as `SystemReleaseClasspath.java` are maintained
as ordinary project source; they are preserved during upgrades and are not reconstructed from upstream.

The baseline comes from published Maven source JARs, so it corresponds to the binaries being packaged. The tool reads the ECJ
version from JDT Core's published POM; the two versions can differ (for example, JDT Core `3.47.0` uses ECJ `3.46.100`).
Eclipse release tags such as `R4_41` remain useful for browsing upstream changes. Reapplying the patches requires neither selecting
historical override commits nor keeping a checkout of the Eclipse repository.

Run the tool with Python 3.9+ and Git installed. The examples below assume the repository root as the working directory:

| Command after `python3 scripts/patchECJ.py` | What it does |
| --- | --- |
| `check` | Verifies the current dependency version, pinned artifact checksums, patch list, and exact reconstruction of the replacement sources. Leaves project files untouched. |
| `update VERSION` | Prepares the given JDT Core release, reapplies and refreshes all patches, then writes the verified sources, patches, baseline metadata, and dependency version back to the project. |
| `edit PATCH.patch` | Opens a temporary workspace for changing an existing patch, or appending a new patch. Prints where to edit the Java sources and how to continue. Project files are updated only after continuation succeeds. |
| `continue WORKSPACE` | Regenerates the pending patch from the edited workspace sources, applies the remaining patches, and writes the complete result back after verification. |
| `continue --drop WORKSPACE` | Explicitly removes an empty pending patch, for example when upstream has incorporated the fix, and resumes the remaining patches. |

Run `python3 scripts/patchECJ.py --help` for the command list, or add `--help` after a command for its options.
The tool changes working-tree files; Git staging, commits, and pushes remain manual. Building the package and validating it in
SonarJava are separate steps described below. Ordinary Maven builds do not run the maintenance tool; CI runs its reconstruction
check before building.

## Updating `eclipse.jdt.core`

The Eclipse IDE follows a quarterly release schedule (typically March, June, September, December). You can find the release history on [wikipedia](https://en.wikipedia.org/wiki/Eclipse_(software)).

Compare the `org.eclipse.jdt:org.eclipse.jdt.core` version in [pom.xml](pom.xml) with the published releases at:

 * https://central.sonatype.com/artifact/org.eclipse.jdt/org.eclipse.jdt.core
 * https://mvnrepository.com/artifact/org.eclipse.jdt/org.eclipse.jdt.core

```bash
# Verify the existing dependency, baseline checksums, complete patch series, and generated sources.
python3 scripts/patchECJ.py check

# Replace 3.47.0 with the target published JDT Core release.
python3 scripts/patchECJ.py update 3.47.0
```

Both commands first read artifacts from `~/.m2/repository`, and download missing ones into a temporary workspace, retrying transient
failures (network errors, HTTP 429 and 5xx) up to three times. Downloads come from Maven Central by default. When
`SONARSOURCE_REPOSITORY_URL` is set, as the `build-maven` action does in CI, they come from that repox repository instead, authenticated
with `ARTIFACTORY_ACCESS_TOKEN` (and `ARTIFACTORY_USERNAME`) over HTTPS only; use `--repository-url URL` to select another repository
(credentials are never sent to it). The tool never writes to the local Maven repository. Use `--offline` to require cached artifacts, or
`--maven-repo PATH` to select a different local repository. These options follow the command, for example
`python3 scripts/patchECJ.py check --offline`.

`update` also compares every artifact it is about to pin (including cached ones) with the `.sha1` files published next to it in the
repository, so a corrupted or tampered local copy cannot become the new baseline. With `--offline` this verification is impossible and
the tool prints a warning. `check` additionally verifies that the `maven-shade-plugin` filter for `org.eclipse.jdt:*` in `pom.xml`
excludes the `.class` and `.java` files of every replacement source, so the shaded jar cannot contain the unpatched upstream classes.

An update proceeds in a temporary workspace:

1. Reconstruct and verify the current replacement sources before attempting to upgrade them.
2. Resolve the target JDT Core and ECJ versions and obtain the pristine replacement sources from their published artifacts.
3. Apply every patch in `patches/series` in order. Regenerate each successful patch against the target sources while preserving its
   description and its place in the series. If a patch conflicts, pause for the manual resolution described below.
4. Verify that the refreshed series recreates the staged result, then write the replacement sources, `pom.xml`, patches, and
   `patches/upstream.json` back to the project together.

Refreshing a patch updates its diff to match the new upstream implementation; it preserves the edits introduced by that patch.
Successful textual application still needs behavioral validation through the tests and downstream checks described below.

### Resolving an upgrade conflict

If a patch cannot be applied, the command exits unsuccessfully and prints the retained workspace path. Project files have not been
changed. `work/` contains the target upstream sources with all earlier patches applied; `before/` is the state immediately before
the pending patch. Application uses `git apply --check` before `git apply`, without partial `.rej` application.

1. Inspect the named patch under the workspace's `patches/` directory and the upstream changes.
2. Edit the managed Java files in `WORKSPACE/work/` to implement that patch's intended behavior. Preserve the earlier patches.
3. Update its description in `WORKSPACE/patches/PATCH.patch` if needed; the diff is regenerated from the edited Java files.
4. Resume with:

```bash
python3 scripts/patchECJ.py continue WORKSPACE
```

Printed continuation commands use the current Python interpreter and absolute paths, so they can be run from any directory.
The tool refreshes only the pending patch and then applies the remaining patches in order. A later conflict pauses in the same way.
If upstream has incorporated a patch completely, leave `work/` identical to `before/` for that step and explicitly remove it with:

```bash
python3 scripts/patchECJ.py continue --drop WORKSPACE
```

An empty pending patch is otherwise an error, so accidentally continuing an unresolved conflict cannot silently discard a fix.
To abandon a staged update, discard its temporary workspace; project files are updated only after all patches have been resolved and verified.

### Maintaining patches

`patches/series` is the authoritative application order. Every `*.patch` in `patches/` must appear exactly once. Patches are grouped
by behavior, can affect multiple replacement classes, and may depend on earlier patches. Their headers document the purpose,
upstream context, and relevant tests. [patches/upstream.json](patches/upstream.json) pins the JDT Core and ECJ versions, SHA-256 checksums of their
POMs and source JARs, and the mapping from upstream sources to local replacement files.

Maintain existing patches through a temporary edit session:

```bash
python3 scripts/patchECJ.py edit 0004-select-system-release-api.patch
# Edit the files under the printed WORKSPACE/work/ and, if needed, the patch description.
python3 scripts/patchECJ.py continue WORKSPACE
```

For a new behavior, pass a new patch filename to `edit`; it is appended after all existing patches. Supply a meaningful patch header
in the printed workspace before continuing. To remove an existing patch, start an edit session for it, restore the managed files in
`work/` to their `before/` contents, and use `continue --drop`. Later patches may need adaptation if they depended on the removed fix.

Edit the staged copies of upstream classes, rather than editing the committed replacement sources directly. Local helpers, tests,
and Maven configuration remain ordinary project files and do not belong in upstream patches. To override another upstream class,
add its source/destination mapping to the manifest, initially copy its pristine source into `src/main/java`, and update the Shade
exclusions before starting a patch edit session. This makes the new file part of reconstruction and preserves the existing fixes.

Before submitting a change, run:

```bash
python3 scripts/patchECJ.py check
mvn verify
```

CI runs the reconstruction check before the Maven build. Reconstruction detects missing patches, dependency
or baseline drift, and source changes that were not recorded in a patch. It does not replace behavioral tests or downstream validation.
The Maven integration tests exercise the shaded JAR, including the replacement `ASTParser`, after packaging.

### Update on sonar-java
Once we have a branch of `sonar-java-jdt` with the new version, we can test that version on sonar-java, either using a
`SNAPSHOT` version when installed locally, or a build version when there is a PR on `sonar-java-jdt`.
The dependency on `sonar-java-jdt` is in the `sonar-java-frontend module`, artifact `jdt-package`.
When the `sonar-java-jdt` version is updated there may be compilation errors caused by changes in the API that need to be fixed on
the `sonar-java` side.

### Validation on sonar-java

We need to check the new version does not cause issue on sonar-java, in particular checking peach results are not badly impacted.

As an optional step, it is a good idea to look at the diff for the parts of the `eclipse-jdt-core` library that are used in `sonar-java`.

If possible, we should also look for example repositories that use features of the new Java versions, and add them on peach.

### Release sonar-java-jdt
Once we validated that the new version of `sonar-java-jdt` works with `sonar-java` (using peach results and possibly new projects), we need to release 
`sonar-java-jdt` so that `sonar-java` does not depend on an unreleased version.

## Modifications

The current modifications are maintained as four patches:

| Patch | Purpose |
| --- | --- |
| `0001-preserve-problem-context.patch` | Avoid aborting analysis on unreportable semantic problems and preserve the problem context. |
| `0002-tolerate-missing-type-assertion.patch` | Tolerate methods incorrectly tagged as containing missing types. |
| `0003-allow-projectless-system-library.patch` | Support projectless environments with custom platform classpaths, including Android. |
| `0004-select-system-release-api.patch` | Delegate platform API selection to the separately maintained `SystemReleaseClasspath` helper. |

The following notes describe historical baseline imports. The current updater uses Maven sources and the checked-in series instead
of these manual downloads or historical override commits.

### org.eclipse.jdt:org.eclipse.jdt.core 3.46.0

In order to update `org.eclipse.jdt.core` to 3.46.0, the following files have been downloaded and patched to fix the `(...) is wrongly tagged as containing missing types` in sanity tests and the `Missing system library` problem in `ASTParser.getClasspath(...)`.
`ProblemHandler.java` was unchanged upstream between R4_38 and R4_40, so it did not need to be re-fetched.

```bash
curl -sSLf -o - https://raw.githubusercontent.com/eclipse-jdt/eclipse.jdt.core/R4_40/org.eclipse.jdt.core.compiler.batch/src/org/eclipse/jdt/internal/compiler/problem/ProblemReporter.java > src/main/java/org/eclipse/jdt/internal/compiler/problem/ProblemReporter.java

curl -sSLf -o - https://raw.githubusercontent.com/eclipse-jdt/eclipse.jdt.core/R4_40/org.eclipse.jdt.core/dom/org/eclipse/jdt/core/dom/ASTParser.java > src/main/java/org/eclipse/jdt/core/dom/ASTParser.java
```

### org.eclipse.jdt:org.eclipse.jdt.core 3.41.0

In order to update `org.eclipse.jdt.core` to 3.41.0, the following files have been downloaded and patched to fix the `referenceContext == null` problem
, the `Missing system library` problem in `ASTParser.getClasspath(...)` and the `(...) is wrongly tagged as containing missing types` in sanity tests.

```bash
curl -sSLf -o - https://raw.githubusercontent.com/eclipse-jdt/eclipse.jdt.core/R4_35/org.eclipse.jdt.core.compiler.batch/src/org/eclipse/jdt/internal/compiler/problem/ProblemHandler.java > src/main/java/org/eclipse/jdt/internal/compiler/problem/ProblemHandler.java

curl -sSLf -o - https://raw.githubusercontent.com/eclipse-jdt/eclipse.jdt.core/R4_35/org.eclipse.jdt.core.compiler.batch/src/org/eclipse/jdt/internal/compiler/problem/ProblemReporter.java > src/main/java/org/eclipse/jdt/internal/compiler/problem/ProblemReporter.java

curl -sSLf -o - https://raw.githubusercontent.com/eclipse-jdt/eclipse.jdt.core/R4_35/org.eclipse.jdt.core/dom/org/eclipse/jdt/core/dom/ASTParser.java > src/main/java/org/eclipse/jdt/core/dom/ASTParser.java
```

### org.eclipse.jdt:org.eclipse.jdt.core 3.39.0

In order to update `org.eclipse.jdt.core` to 3.39.0, the following files have been downloaded and patched to fix the `referenceContext == null problem`:
```bash
curl -sSLf -o - https://raw.githubusercontent.com/eclipse-jdt/eclipse.jdt.core/R4_33/org.eclipse.jdt.core.compiler.batch/src/org/eclipse/jdt/internal/compiler/problem/ProblemHandler.java > src/main/java/org/eclipse/jdt/internal/compiler/problem/ProblemHandler.java

curl -sSLf -o - https://raw.githubusercontent.com/eclipse-jdt/eclipse.jdt.core/R4_33/org.eclipse.jdt.core.compiler.batch/src/org/eclipse/jdt/internal/compiler/problem/ProblemReporter.java > src/main/java/org/eclipse/jdt/internal/compiler/problem/ProblemReporter.java

```

### org.eclipse.jdt:org.eclipse.jdt.core 3.38.0

In order to update `org.eclipse.jdt.core` to 3.38.0, the following files have been downloaded and patched to fix the `referenceContext == null problem`:
```bash
curl -sSLf -o - https://raw.githubusercontent.com/eclipse-jdt/eclipse.jdt.core/R4_32/org.eclipse.jdt.core.compiler.batch/src/org/eclipse/jdt/internal/compiler/problem/ProblemHandler.java > src/main/java/org/eclipse/jdt/internal/compiler/problem/ProblemHandler.java

curl -sSLf -o - https://raw.githubusercontent.com/eclipse-jdt/eclipse.jdt.core/R4_32/org.eclipse.jdt.core.compiler.batch/src/org/eclipse/jdt/internal/compiler/problem/ProblemReporter.java > src/main/java/org/eclipse/jdt/internal/compiler/problem/ProblemReporter.java

```

### org.eclipse.jdt:org.eclipse.jdt.core 3.37.0

JDT core 3.36.0 and 3.37.0 introduce a bug which prevent analysis of files when there are semantic problems.
The `ProblemReporter` could have a null `referenceContext` during the resolution of `unknown types` which produces
`AbortCompilation` exception, instead of adding the semantic problems to the compilation result.
The logic in `ProblemReporter` is sometimes wrong, and it wrongly set `referenceContext` to null.
For the Java analyzer, it's an important problem because even when the semantic is not 100% resolved, we still want
to analyze the source code. And this bug also silently prevent the following files in the same compilation batch to be analyzed.

Modified source code in the `SONARJAVA-4 Fix the referenceContext == null problem` commit:
* https://github.com/eclipse-jdt/eclipse.jdt.core/blob/R4_31/org.eclipse.jdt.core.compiler.batch/src/org/eclipse/jdt/internal/compiler/problem/ProblemHandler.java
* https://github.com/eclipse-jdt/eclipse.jdt.core/blob/R4_31/org.eclipse.jdt.core.compiler.batch/src/org/eclipse/jdt/internal/compiler/problem/ProblemReporter.java
