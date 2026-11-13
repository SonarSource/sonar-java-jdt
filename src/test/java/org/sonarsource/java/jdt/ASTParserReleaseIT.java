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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FileASTRequestor;
import org.eclipse.jdt.core.dom.SystemReleaseClasspath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks, through the real {@link ASTParser} of the shaded jar, that the patched {@code ASTParser#getClasspath()} gives
 * the API of the requested release to ECJ.
 *
 * <p>The API of a release is observed through two types of the JDK:
 * <ul>
 *   <li>{@code java.util.jar.Pack200}, which exists in Java 11 and was removed in Java 14;</li>
 *   <li>{@code java.lang.Record}, which was added in Java 16.</li>
 * </ul>
 *
 * <p>Unit tests cannot cover this code: they would load the patched {@code ASTParser} next to the signed classes of the
 * original jar. That is why this is an integration test, executed by the failsafe plugin after the jar is built.
 */
class ASTParserReleaseIT {

  private static final String PACK200 = "java.util.jar.Pack200";
  private static final String RECORD = "java.lang.Record";

  private static final String SOURCE = """
    class Example {
      java.util.jar.Pack200 removedInJdk14;
      java.lang.Record addedInJdk16;
    }
    """;

  @TempDir
  Path temp;

  @BeforeAll
  static void requires_a_jdk_able_to_serve_older_releases() {
    assumeTrue(new File(System.getProperty("java.home"), "lib/ct.sym").isFile(), "the running JVM has no lib/ct.sym");
    assumeTrue(Runtime.version().feature() > 17, "the running JVM must be newer than Java 17");
  }

  @Test
  void release_11_sees_the_api_of_java_11() {
    assertThat(unresolved(parse(SOURCE, release("11")))).containsExactly(RECORD);
  }

  @Test
  void release_17_sees_the_api_of_java_17() {
    assertThat(unresolved(parse(SOURCE, release("17")))).containsExactly(PACK200);
  }

  @Test
  void without_release_the_api_of_the_running_jvm_is_seen() {
    assertThat(unresolved(parse(SOURCE, new HashMap<>(JavaCore.getOptions())))).containsExactly(PACK200);
  }

  @Test
  void release_not_older_than_the_running_jvm_keeps_the_api_of_the_running_jvm() {
    int feature = Runtime.version().feature();
    assertThat(unresolved(parse(SOURCE, release(Integer.toString(feature))))).containsExactly(PACK200);
    assertThat(unresolved(parse(SOURCE, release(Integer.toString(feature + 1))))).containsExactly(PACK200);
  }

  /**
   * Java 8 and older are never served from {@code ct.sym}: it only holds the supported public API, which is less than what
   * a JDK 8 offers ({@code sun.misc.Unsafe}, the Java EE modules...). Such projects need an actual JDK of their version.
   */
  @Test
  void release_8_and_below_keep_the_api_of_the_running_jvm() {
    assertThat(unresolved(parse(SOURCE, release("8")))).containsExactly(PACK200);
    assertThat(unresolved(parse(SOURCE, release("7")))).containsExactly(PACK200);
  }

  @Test
  void jdk_given_explicitly_provides_the_release_api() {
    Map<String, String> options = release("11");
    options.put(SystemReleaseClasspath.OPTION_JAVA_HOME, System.getProperty("java.home"));

    assertThat(unresolved(parse(SOURCE, options))).containsExactly(RECORD);
  }

  @Test
  void jdk_without_ct_sym_keeps_the_api_of_the_running_jvm() throws IOException {
    Path jreLikeHome = Files.createDirectories(temp.resolve("jre-like"));
    Files.writeString(jreLikeHome.resolve("release"), "JAVA_VERSION=\"21.0.2\"\n");
    Map<String, String> options = release("11");
    options.put(SystemReleaseClasspath.OPTION_JAVA_HOME, jreLikeHome.toString());

    assertThat(unresolved(parse(SOURCE, options))).containsExactly(PACK200);
  }

  @Test
  void classpath_of_the_project_is_kept() throws IOException {
    assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "no Java compiler available");
    Path library = compileLibrary();
    String source = """
      class Example {
        library.Library fromClasspath;
        java.util.jar.Pack200 removedInJdk14;
        java.lang.Record addedInJdk16;
      }
      """;

    assertThat(unresolved(parse(source, release("11"), true, library.toString()))).containsExactly(RECORD);
    assertThat(unresolved(parse(source, release("11"), true))).containsExactlyInAnyOrder("library", RECORD);
  }

  @Test
  void batch_mode_sees_the_release_api() throws IOException {
    Path file = Files.writeString(temp.resolve("Example.java"), SOURCE);
    Set<String> unresolved = new TreeSet<>();

    ASTParser parser = newParser(release("11"), true);
    parser.createASTs(new String[] {file.toString()}, new String[] {"UTF-8"}, new String[0], new FileASTRequestor() {
      @Override
      public void acceptAST(String sourceFilePath, CompilationUnit ast) {
        unresolved.addAll(unresolved(ast));
      }
    }, null);

    assertThat(unresolved).containsExactly(RECORD);
  }

  @Test
  void release_is_ignored_when_the_running_vm_bootclasspath_is_not_requested() {
    CompilationUnit unit = parse("class Example { String text; }", release("11"), false);

    assertThat(unresolved(unit)).containsExactly("String");
  }

  private static Map<String, String> release(String release) {
    Map<String, String> options = new HashMap<>(JavaCore.getOptions());
    JavaCore.setComplianceOptions(release, options);
    options.put(SystemReleaseClasspath.OPTION_RELEASE, release);
    return options;
  }

  private static CompilationUnit parse(String source, Map<String, String> options) {
    return parse(source, options, true);
  }

  private static CompilationUnit parse(String source, Map<String, String> options, boolean includeRunningVMBootclasspath, String... classpath) {
    ASTParser parser = newParser(options, includeRunningVMBootclasspath, classpath);
    parser.setUnitName("Example.java");
    parser.setSource(source.toCharArray());
    return (CompilationUnit) parser.createAST(null);
  }

  private static ASTParser newParser(Map<String, String> options, boolean includeRunningVMBootclasspath, String... classpath) {
    ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
    parser.setCompilerOptions(options);
    parser.setEnvironment(classpath, new String[0], new String[0], includeRunningVMBootclasspath);
    parser.setResolveBindings(true);
    parser.setBindingsRecovery(true);
    return parser;
  }

  private static Set<String> unresolved(CompilationUnit unit) {
    return Arrays.stream(unit.getProblems())
      .filter(IProblem::isError)
      .map(IProblem::getMessage)
      .filter(message -> message.endsWith(" cannot be resolved to a type"))
      .map(message -> message.substring(0, message.length() - " cannot be resolved to a type".length()))
      .collect(Collectors.toCollection(TreeSet::new));
  }

  private Path compileLibrary() throws IOException {
    Path sources = Files.createDirectories(temp.resolve("library-sources/library"));
    Path classes = Files.createDirectories(temp.resolve("library-classes"));
    Path file = Files.writeString(sources.resolve("Library.java"), "package library; public class Library { }\n");
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    int result = compiler.run(null, null, null, "-d", classes.toString(), file.toString());
    assertThat(result).isZero();
    return classes;
  }

}
