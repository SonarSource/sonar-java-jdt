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

## Fixing bugs in JDT Core

### Where to find the source code

The JDT source code is in this [eclipse-jdt/eclipse.jdt.core](https://github.com/eclipse-jdt/eclipse.jdt.core) repository.

### Prerequisites

* Depending of the configuration of the maven build, you may need JDK 8, 11, 17, 18, 19, 20, 21 22
* The default Java version should be 17 or greater.
* Once installed, the different JDK should be referenced in the `${HOME}/.m2/toolchains.xml` file:
```xml
<?xml version="1.0" encoding="UTF8"?>
<toolchains>
  <toolchain>
    <type>jdk</type>
    <provides>
      <id>JavaSE-1.8</id>
    </provides>
    <configuration>
       <jdkHome>${env.HOME}/.sdkman/candidates/java/8</jdkHome>
    </configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides>
      <id>JavaSE-11</id>
    </provides>
    <configuration>
       <jdkHome>${env.HOME}/.sdkman/candidates/java/11</jdkHome>
    </configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides>
      <id>JavaSE-17</id>
    </provides>
    <configuration>
       <jdkHome>${env.HOME}/.sdkman/candidates/java/17</jdkHome>
    </configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides>
      <id>JavaSE-18</id>
    </provides>
    <configuration>
       <jdkHome>${env.HOME}/.sdkman/candidates/java/18</jdkHome>
    </configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides>
      <id>JavaSE-19</id>
    </provides>
    <configuration>
       <jdkHome>${env.HOME}/.sdkman/candidates/java/19</jdkHome>
    </configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides>
      <id>JavaSE-20</id>
    </provides>
    <configuration>
       <jdkHome>${env.HOME}/.sdkman/candidates/java/20</jdkHome>
    </configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides>
      <id>JavaSE-21</id>
    </provides>
    <configuration>
       <jdkHome>${env.HOME}/.sdkman/candidates/java/21</jdkHome>
    </configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides>
      <id>JavaSE-22</id>
    </provides>
    <configuration>
       <jdkHome>${env.HOME}/.sdkman/candidates/java/22</jdkHome>
    </configuration>
  </toolchain>
</toolchains>
```

### What IDE to use

`eclipse.jdt.core` is far from being a standard maven project, it's a mix of a lot of different technologies mixed together
(tycho, eclipse .project .classpath MANIFEST.MF, underlying "ant" tasks and other atrocity).
So the only IDE that is able to open it is `Eclipse IDE for Eclipse Committers`. It's not possible with `IntelliJ IDEA`.

### How to install and configure the IDE

`eclipse.jdt.core` is a project hard to configure to be able to compile and debug the code.
Because it depends on some `Eclipse Platform` modules that are not available in maven central.
So the manual configuration could be a good way to learn or loose your time depending of the result:
https://github.com/eclipse-jdt/eclipse.jdt.core/wiki/FAQ-JDT-Core-Committer#setup-ide-and-workspace

An alternative way, and probably the easiest way, is to use the installer provided by Eclipse Oomph in conjunction with the [JDTConfiguration.setup](https://github.com/eclipse-jdt/eclipse.jdt/blob/master/org.eclipse.jdt.releng/JDTConfiguration.setup) file.

The documentation to use `JDTConfiguration.setup` can be generated by the setup file itself through the following link
(note: it's not possible to replace `master` by the branch on which you plan to work, like `R4_32_maintenance`)
https://www.eclipse.org/setups/installer/?url=https://raw.githubusercontent.com/eclipse-jdt/eclipse.jdt/master/org.eclipse.jdt.releng/JDTConfiguration.setup&show=true

The procedure consist of:

* Download the `JDTConfiguration.setup` related to the branch you want to work on, for example `R4_32_maintenance`:
  ```bash
  JDT_BRANCH="R4_32_maintenance" && \
    curl -sSLf -o JDTConfiguration.setup "https://raw.githubusercontent.com/eclipse-jdt/eclipse.jdt/${JDT_BRANCH}/org.eclipse.jdt.releng/JDTConfiguration.setup"
  ```
* Download the Eclipse Installer for your platform:
  * [Installer Windows 64 Bit](https://www.eclipse.org/downloads/download.php?file=/oomph/products/eclipse-inst-jre-win64.exe)
  * [Installer Mac OS 64 Bit](https://www.eclipse.org/downloads/download.php?file=/oomph/products/eclipse-inst-jre-mac64.dmg)
  * [Installer Linux 64 Bit](https://www.eclipse.org/downloads/download.php?file=/oomph/products/eclipse-inst-jre-linux64.tar.gz)
* Run the `Eclipse Installer` and drag and drop the `JDTConfiguration.setup` file into the installer window top `Product` section.
* You should see a form with different variables to define, finish to define all of them, for example:
  * Root install folder: /Users/alban/dev/JDTConfiguration
  * JRE 17 Location: /Users/alban/.sdkman/candidates/java/17
* Run the installation (20 minutes)
* At the end the eclipse IDE should open with the `eclipse.jdt.core` project loaded. (wait 3 minutes for the Eclipse Updater to finish)
* But you are not on the right branch, so you need to switch to the branch you want to work on, for example `R4_32_maintenance`:
  * `Window` -> `Show View` -> `Git Repositories`
  * Right click on each repositories -> `Switch To` -> `Other...` -> `R4_32_maintenance` -> `Check Out`
  ```bash
  # Or in the terminal go to the `git` folder of the installation root
  cd "${HOME}/alban/dev/JDTConfiguration/jdt/git"
  # And run only one command to switch all repositories to the branch `R4_32_maintenance`
  ls -1 | xargs -I{} git -C "{}" checkout R4_32_maintenance
  ```

### How to run several tests

Open a test file, for example:

`Navigate` -> `Open Resource...` -> type `UnnamedPatternsAndVariablesTest.java` -> `Open`

Right click on the class name `Run As` -> `JUnit Plugin Tets`

To run only for a specific version of Java, change the run configuration using the `compliance` property, for example for Java 22:
* `Run` -> `Run Configurations...` -> `JUnit Plugin Tests` -> `UnnamedPatternsAndVariablesTest`
  * `Arguments` -> `VM arguments` -> add `-Dcompliance=22`
  * `Main` -> `Java Runtime Environment` -> `Execution Environment` -> `JavaSE-22`

### How to run one test

Some of the tests can not be run directly by right-clicking on one test method from the IDE, but it's possible to only run one test using the static field `TESTS_NAMES`.
Like for example in the `UnnamedPatternsAndVariablesTest.java` file, we can replace:
```java
	static {
		//	TESTS_NAMES = new String [] { };
	}
```
by
```java
	static {
		TESTS_NAMES = new String [] { "testInstanceOfPatternMatchingWithMixedPatterns2" };
	}
```

### Where is the documentation

* GitHub [eclipse.jdt.core/README.md](https://github.com/eclipse-jdt/eclipse.jdt.core/blob/master/README.md)
* GitHub [eclipse-jdt/CONTRIBUTING.md](https://github.com/eclipse-jdt/.github/blob/main/CONTRIBUTING.md)
* GitHub [eclipse.jdt.core/wiki](https://github.com/eclipse-jdt/eclipse.jdt.core/wiki)
* Project Page [JDT Core Component](https://eclipse.dev/jdt/core/index.php)
* Project Page [Eclipse Java development tools (JDT)](https://eclipse.dev/jdt/)
* help.eclipse.org [JDT Plug-in Developer Guide > Programmer's Guide > JDT Core](https://help.eclipse.org/latest/index.jsp?topic=/org.eclipse.jdt.doc.isv/guide/jdt_api_compile.htm)
* help.eclipse.org [JDT Plug-in Developer Guide](https://help.eclipse.org/latest/index.jsp?nav=/3)
* Note: The former wiki is here https://wiki.eclipse.org/JDT_Core_Programmer_Guide/ (end of life April 2026)

## License

`Eclipse Public License - v 2.0` see [LICENSE](LICENSE).
