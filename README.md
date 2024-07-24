# Packaging of Eclipse's Java Development Tools for SonarJava

## Modifications

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

## Understanding Eclipse JDT versions

The source code of the Eclipse JDT project is available on GitHub:
https://github.com/eclipse-jdt/eclipse.jdt.core

Each 3 months, a new version of the Eclipse Platform is released, and JDT is part of it, but with a different versioning number for each artifact.

First we look at the `Latest Release` version of the Eclipse Platform on this page https://download.eclipse.org/eclipse/downloads/
For example, in July 2024 it was the one-month-old `4.32`:
```text
Latest Release
4.32 01 Jun 2024
4.31 29 Feb 2024
4.30 01 Dec 2023
```

Then, if we want to know the versions of the JDT core artifacts that match Eclipse Platform 4.32, we look at the `R4_32` or `R4_32_maintenance` branch of the
JDT core repository, and we ignore the `-SNAPSHOT` suffix.

For example on the `R4_32` branch of the `eclipse-jdt/eclipse.jdt.core` repository:

* [R4_32 - /pom.xml](https://github.com/eclipse-jdt/eclipse.jdt.core/blob/R4_32/pom.xml#L18) there is `<version>4.32.0-SNAPSHOT</version>` => It was part of the `4.32.0` release of the Eclipse Platform.
* [R4_32 - /org.eclipse.jdt.core/pom.xml#L18](https://github.com/eclipse-jdt/eclipse.jdt.core/blob/R4_32/org.eclipse.jdt.core/pom.xml#L20) there is `<version>3.38.0-SNAPSHOT</version>` => it was released `eclipse.jdt.core:org.eclipse.jdt.core:3.38.0` in maven central.

But to find other transitive dependencies, we can not use maven, because the dependencies are not declared in the `pom.xml` file:
```
mvn -f org.eclipse.jdt.core/pom.xml dependency:tree
```
The above command does not display the dependencies because it seems that the `dependency:tree` plugin is not compatible with the `tycho` plugin that inject the dependencies.

The dependencies are declared elsewhere, probably in the `Require-Bundle:` section of the `org.eclipse.jdt.core/MANIFEST.MF` file:
[R4_32 - /org.eclipse.jdt.core/META-INF/MANIFEST.MF:45](https://github.com/eclipse-jdt/eclipse.jdt.core/blob/R4_32/org.eclipse.jdt.core/META-INF/MANIFEST.MF#L45)

Fortunately, during the release, the `pom.xml` file is uploaded to maven central with the correct list of dependencies [org.eclipse.jdt:org.eclipse.jdt.core:3.38.0](https://repo1.maven.org/maven2/org/eclipse/jdt/org.eclipse.jdt.core/3.38.0/org.eclipse.jdt.core-3.38.0.pom)

So it's possible to download the generated pom file:
```
mvn dependency:get -DgroupId=org.eclipse.jdt -DartifactId=org.eclipse.jdt.core -Dversion=3.38.0
```
And display the dependency tree:
```
mvn -f ~/.m2/repository/org/eclipse/jdt/org.eclipse.jdt.core/3.38.0/org.eclipse.jdt.core-3.38.0.pom dependency:tree
```
The output is:
```
org.eclipse.jdt:org.eclipse.jdt.core:jar:3.38.0
+- org.eclipse.platform:org.eclipse.core.resources:jar:3.20.200:compile
|  +- org.eclipse.platform:org.eclipse.core.expressions:jar:3.9.400:compile
|  \- org.eclipse.platform:org.eclipse.osgi:jar:3.20.0:compile
+- org.eclipse.platform:org.eclipse.core.runtime:jar:3.31.100:compile
|  +- org.eclipse.platform:org.eclipse.equinox.common:jar:3.19.100:compile
|  +- org.eclipse.platform:org.eclipse.core.jobs:jar:3.15.300:compile
|  +- org.eclipse.platform:org.eclipse.equinox.registry:jar:3.12.100:compile
|  +- org.eclipse.platform:org.eclipse.equinox.preferences:jar:3.11.100:compile
|  |  \- org.osgi:org.osgi.service.prefs:jar:1.1.2:compile
|  |     \- org.osgi:osgi.annotation:jar:8.0.1:compile
|  +- org.eclipse.platform:org.eclipse.core.contenttype:jar:3.9.400:compile
|  \- org.eclipse.platform:org.eclipse.equinox.app:jar:1.7.100:compile
+- org.eclipse.platform:org.eclipse.core.filesystem:jar:1.10.400:compile
+- org.eclipse.platform:org.eclipse.text:jar:3.14.100:compile
|  \- org.eclipse.platform:org.eclipse.core.commands:jar:3.12.100:compile
+- org.eclipse.platform:org.eclipse.team.core:jar:3.10.400:compile
|  \- org.eclipse.platform:org.eclipse.compare.core:jar:3.8.500:compile
\- org.eclipse.jdt:ecj:jar:3.38.0:compile
```

The `dependency:tree` plugin does not allow to exclude optional dependencies, but the `maven-shade-plugin` does not include them by default.
For example, the `org.eclipse.team.core` dependency is marked `<optional>true</optional>` in the [org.eclipse.jdt.core-3.38.0.pom](https://repo1.maven.org/maven2/org/eclipse/jdt/org.eclipse.jdt.core/3.38.0/org.eclipse.jdt.core-3.38.0.pom) file. So `org.eclipse.team.core` is not included in the shaded jar.

When changing the `pom.xml` dependencies, we should keep in mind to compare the new content of the shaded jar with the previous one to ensure that
the dependencies are correctly included or excluded. At each state of the `pom.xml` file modification, we can generate a text description of its content using the following command:

```bash
(
  CURRENT_DATE="$(date +"%Y.%m.%d-%Hh%Mm%Ss")" && \
  mvn clean package && \
    mkdir target/jdt-package-jar-content && \
    cd    target/jdt-package-jar-content && \
    jar xf ../jdt-package-*-SNAPSHOT.jar && \
    find . -type f | sort                   > "../../jdt-package-jar-${CURRENT_DATE}-content.txt" && \
    find . -type f | sort | xargs sha256sum > "../../jdt-package-jar-${CURRENT_DATE}-content-hash.txt" && \
    cd ../..
)
```

## License

`Eclipse Public License - v 2.0` see [LICENSE](LICENSE).
