# Packaging of Eclipse's Java Development Tools for SonarJava


## Updating `eclipse.jdt.core`

The Eclipse IDE follows a quarterly release schedule (typically March, June, September, December). You can find the release history on [wikipedia](https://en.wikipedia.org/wiki/Eclipse_(software)).

To update ECJ (Eclipse Compiler for Java), you usually need the `eclipse.jdt.core` tag corresponding to the latest stable Eclipse IDE release.

1.  **Identify the Version:** Determine the version number of the target Eclipse IDE release (e.g., `4.31` for the 2024-03 release) using the link above.
2.  **Construct the Tag:** These tags follow the pattern `R<majorVersion>_<minorVersion>`. For example, Eclipse version `4.31` corresponds to the tag `R4_31`.
3.  **Verify the Tag:** Confirm that this exact tag exists in the `eclipse.jdt.core` repository. You can check the available tags directly [here](https://github.com/eclipse-jdt/eclipse.jdt.core).
4.  **Update `org.eclipse.jdt.core` version:** Find the version number of `jdt.core` corresponding to the release tag in the `org.eclipse.jdt.core/pom.xml` file and then update `sonar-java-jdt/pom.xml`. For example, for the R4_35 tag, the eclipse.jdt.core release was 3.41.0 because of [jdt.core/pom.xml](https://github.com/eclipse-jdt/eclipse.jdt.core/blob/R4_35/org.eclipse.jdt.core/pom.xml#L20)
5.  **Apply the modifications:** you can use the `overrideECJ.sh` script or read the instructions below.

## Modifications

Don't forget to update `overrideECJ.sh` if you change how you override `ECJ` files.

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

Modified source code in the `SONARJAVA-4921 Fix the referenceContext == null problem` commit:
* https://github.com/eclipse-jdt/eclipse.jdt.core/blob/R4_31/org.eclipse.jdt.core.compiler.batch/src/org/eclipse/jdt/internal/compiler/problem/ProblemHandler.java
* https://github.com/eclipse-jdt/eclipse.jdt.core/blob/R4_31/org.eclipse.jdt.core.compiler.batch/src/org/eclipse/jdt/internal/compiler/problem/ProblemReporter.java

## License

`Eclipse Public License - v 2.0` see [LICENSE](LICENSE).
