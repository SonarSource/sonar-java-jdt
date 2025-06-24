# Packaging of Eclipse's Java Development Tools for SonarJava

This repository exists because we need to patch some files from eclipse JDT to fix some issues, but we can only do this in a repository with the same license as Eclipse.
When a new version of JDT is released, we need to update it in `sonar-java-jdt` and reapply the patch.

## License

`Eclipse Public License - v 2.0` see [LICENSE](LICENSE).

## Updating `eclipse.jdt.core`

The Eclipse IDE follows a quarterly release schedule (typically March, June, September, December). You can find the release history on [wikipedia](https://en.wikipedia.org/wiki/Eclipse_(software)).

To know when we can update, we should compare the org.eclipse.jdt:org.eclipse.jdt.core dependency version declared in:
https://github.com/SonarSource/sonar-java-jdt/blob/master/pom.xml#L60
to the newest version available at one of those location:
 * https://central.sonatype.com/artifact/org.eclipse.jdt/org.eclipse.jdt.core
 * https://mvnrepository.com/artifact/org.eclipse.jdt/org.eclipse.jdt.core

To update ECJ (Eclipse Compiler for Java), you usually need the `eclipse.jdt.core` tag corresponding to the latest stable Eclipse IDE release.

1.  **Identify the Version:** Determine the version number of the target Eclipse IDE release (e.g., `4.31` for the 2024-03 release) using the link above.
2.  **Find the corresponding JDT core tag:** These tags follow the pattern `R<majorVersion>_<minorVersion>`. For example, Eclipse version `4.31` corresponds to the tag `R4_31`. 
    Check that tag exists in the `eclipse.jdt.core` repository. You can check the available tags directly [here](https://github.com/eclipse-jdt/eclipse.jdt.core).
3. **Update `org.eclipse.jdt.core` version and reapply the patches:** the script `overrideECJ.sh` can take care of these steps.

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

This sections lists the modifications that are contained in the patches and were made to the JDT core files in order to fix some issues that SonarJava has with the JDT core.

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
