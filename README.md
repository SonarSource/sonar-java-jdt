# Packaging of Eclipse's Java Development Tools for SonarJava

## Modifications

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
