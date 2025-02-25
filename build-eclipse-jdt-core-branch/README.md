# Build Eclipse JDT Code into maven local repository

## Why building Eclipse JDT Core from GitHub?

While `sonar-java-jdt` can package any release versions of `org.eclipse.jdt.core` from Maven Central,
`sonar-java-jdt` can not to package the current development version of `org.eclipse.jdt.core` to provide feedback.

## What this project does?

* Clone https://github.com/eclipse-jdt/eclipse.jdt.core
* Build the project to generate, into maven local repository, the jars and maven pom files of `org.eclipse.jdt.core`.
* Recreate `sonar-java-jdt/pom.xml` to depend on the built `org.eclipse.jdt.core` version.
* Rebuild `sonar-java-jdt`.

## How to execute it?

### Use Java 21
```bash
sdk use java 21.0.4-tem
```

## Go to this project directory
```bash
cd build-eclipse-jdt-core-branch
```

### Build and execute this project with the JDT branch name as argument
```bash
mvn clean package exec:java -Dexec.args="BETA_JAVA24"
```

### Run one JDT unit test
```bash
cd workdir/eclipse.jdt.core/org.eclipse.jdt.core.tests.compiler
mvn verify "-Dtest=org.eclipse.jdt.core.tests.compiler.regression.ConditionalExpressionTest" -Dtycho.surefire.argLine="-Dcompliance=21" --batch-mode
```
