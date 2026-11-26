/*
 * Eclipse JDT Core Package
 * Copyright (C) SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * You can redistribute and/or modify this program under the terms of
 * the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
package org.sonarsource.java.jdt;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.batch.BatchCompiler;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.SystemReleaseClasspath;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.problem.ProblemHandler;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CompilerIT {

  @TempDir
  Path temp;

  @ParameterizedTest
  @ValueSource(classes = {ASTParser.class, ProblemHandler.class, ProblemReporter.class, SystemReleaseClasspath.class,
    JavaCore.class, Compiler.class, BatchCompiler.class})
  void verification_loads_the_shaded_jar_with_assertions_enabled(Class<?> type) throws Exception {
    String packageJar = System.getProperty("jdt.package.jar");
    assertThat(packageJar).as("run integration tests with mvn verify to select the packaged JAR").isNotBlank();
    Path expectedJar = Path.of(packageJar).toRealPath();
    Path actualLocation = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();

    assertThat(actualLocation).as("code source of %s", type.getName()).isEqualTo(expectedJar);
    assertThat(type.desiredAssertionStatus()).as("assertions enabled for %s", type.getName()).isTrue();
  }

  @Test
  void compiles_and_executes_records_generics_and_lambdas() throws Exception {
    Path source = Files.writeString(temp.resolve("Example.java"), """
      import java.util.List;
      public class Example {
        record Box<T>(T value) { }
        public static int answer() {
          return new Box<>(List.of(21, 21)).value().stream().mapToInt(value -> value).sum();
        }
      }
      """);
    Path classes = Files.createDirectory(temp.resolve("classes"));
    StringWriter diagnostics = new StringWriter();

    assertThat(compile(source, classes, diagnostics)).as("valid compilation: %s", diagnostics).isTrue();
    assertThat(classes.resolve("Example.class")).isRegularFile();
    assertThat(classes.resolve("Example$Box.class")).isRegularFile();
    try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, null)) {
      assertThat(loader.loadClass("Example").getMethod("answer").invoke(null)).isEqualTo(42);
    }
  }

  @Test
  void rejects_unresolved_types_with_a_diagnostic() throws IOException {
    Path source = Files.writeString(temp.resolve("Invalid.java"), "public class Invalid { Missing value; }");
    Path classes = Files.createDirectory(temp.resolve("classes"));
    StringWriter diagnostics = new StringWriter();

    assertThat(compile(source, classes, diagnostics)).as("invalid compilation: %s", diagnostics).isFalse();
    assertThat(diagnostics.toString()).contains("Invalid.java", "Missing cannot be resolved to a type");
    assertThat(classes.resolve("Invalid.class")).doesNotExist();
  }

  private static boolean compile(Path source, Path classes, StringWriter diagnostics) {
    PrintWriter writer = new PrintWriter(diagnostics);
    return BatchCompiler.compile(new String[]{"-17", "-proc:none", "-encoding", "UTF-8", "-d", classes.toString(), source.toString()}, writer, writer, null);
  }
}
