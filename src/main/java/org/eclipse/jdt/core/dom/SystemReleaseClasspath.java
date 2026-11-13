/*
 * SonarQube Java
 * Copyright (C) SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.jdt.core.dom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jdt.internal.compiler.batch.FileSystem;
import org.eclipse.jdt.internal.compiler.batch.FileSystem.Classpath;

/**
 * Lets {@link ASTParser} see the Java API of a given Java release, instead of the API of the JDK it runs on.
 *
 * <h2>The problem</h2>
 * The compliance level given to the parser ({@code JavaCore.COMPILER_COMPLIANCE} and friends) only controls
 * <em>language</em> features: whether {@code var}, records or switch expressions are accepted. It has no effect on
 * <em>which JDK classes can be resolved</em>. Those come from the classpath, and when
 * {@code ASTParser#setEnvironment(..., includeRunningVMBootclasspath = true)} is used, {@code ASTParser} adds the
 * runtime image ({@code jrt-fs}) of the JVM it is running on.
 *
 * <p>Consequently, when the analyzer runs on JDK 26 and analyzes a project targeting Java 11:
 * <ul>
 *   <li>a use of an API added after 11 (for example {@code java.lang.Record}) resolves successfully, although the
 *   project could not compile;</li>
 *   <li>a use of an API removed before 26 (for example {@code java.util.jar.Pack200}) is reported as an unresolved type,
 *   although it is valid for the project.</li>
 * </ul>
 * The semantic model therefore depends on the JDK that happens to run the analyzer instead of on the project.
 *
 * <h2>The solution</h2>
 * JEP 247 ({@code javac --release N}) solves the same problem for the Java compiler: the JDK ships {@code lib/ct.sym},
 * an archive with the signatures of the public API of every supported older release. ECJ already knows how to read it
 * ({@link FileSystem#getOlderSystemRelease}), but it only exposes this through its batch compiler command line. The DOM
 * {@code ASTParser} has no way to request it: {@code JavaCore.COMPILER_RELEASE} is only honored by the JDT project
 * builder, and the classpath built by {@code ASTParser#getClasspath()} always uses the running VM.
 *
 * <p>This class bridges that gap. The client opts in by adding two entries to the compiler options passed to
 * {@link ASTParser#setCompilerOptions(Map)}:
 * <ul>
 *   <li>{@link #OPTION_RELEASE}: the Java release whose API must be visible, such as {@code "11"};</li>
 *   <li>{@link #OPTION_JAVA_HOME} (optional): home of the JDK providing {@code ct.sym}. Defaults to the
 *   {@code java.home} of the running JVM.</li>
 * </ul>
 * When the option is present and the running-VM bootclasspath was requested, {@code ASTParser#getClasspath()} uses the
 * entry returned by {@link #resolve(Map)} <em>in place of</em> the running-VM bootclasspath. Everything else is
 * untouched: source paths and the project's own jars are added exactly as before, and the real
 * {@code ICompilationUnitResolver} is still used. Since both the single-file ({@code createAST}) and the batch
 * ({@code createASTs}) code paths build their classpath through {@code getClasspath()}, both are covered.
 *
 * <h2>Releases that are not served on purpose</h2>
 * Java 8 and older ({@code release < }{@value #MIN_RELEASE}) are never served from {@code ct.sym}, whatever the JDK:
 * {@link #resolve(Map)} is empty and {@link #isApplicable(Map)} is {@code false}, without a warning. See
 * {@link #MIN_RELEASE} for the reason. Such projects must be analyzed against an actual JDK of their version.
 *
 * <h2>When the release cannot be served</h2>
 * The release is requested by the client, and the JDK that provides {@code ct.sym} may not be able to serve it. In that
 * case {@link #resolve(Map)} logs <b>one warning</b> per distinct (JDK, release) and returns an empty result, and
 * {@link #isApplicable(Map)} returns {@code false}, so that the parser keeps its previous behavior (the API of the
 * running JVM). This happens when:
 * <ul>
 *   <li>{@link #OPTION_RELEASE} is not a number;</li>
 *   <li>the JDK has no {@code lib/ct.sym}, as is the case for some JREs and custom {@code jlink} images;</li>
 *   <li>{@code ct.sym} cannot be read, or does not describe the release (older than what the JDK supports, newer than
 *   the JDK itself, ...). This is what ECJ reports when it initializes the entry, so the decision is exactly the one
 *   ECJ would take later during the parse.</li>
 * </ul>
 * Nothing is logged when {@link #OPTION_RELEASE} is absent: the client did not ask for anything.
 *
 * <h2>Which JDK provides {@code ct.sym}</h2>
 * The JDK given by {@link #OPTION_JAVA_HOME}, or the running JVM when the option is missing. This lets a client use the
 * JDK that the user configured (for example through {@code sonar.java.jdkHome}) even when the analysis itself runs on a
 * JRE that does not ship {@code ct.sym}.
 *
 * <h2>Coordination with the client</h2>
 * When the client passes a JDK through {@code ASTParser#setEnvironment} (for example the {@code lib/jrt-fs.jar} of a
 * configured JDK) it normally disables the running-VM bootclasspath, and this class is then never consulted. A client
 * that wants the release to apply to such a JDK should ask {@link #isApplicable(Map)}, remove that JDK from the
 * classpath it gives to the parser, set {@link #OPTION_JAVA_HOME} to it, and request the running-VM bootclasspath: the
 * entry returned by {@link #resolve(Map)} then takes its place.
 *
 * <h2>Why this lives in {@code org.eclipse.jdt.core.dom}</h2>
 * It is called from the patched {@link ASTParser}, which is itself an override of the upstream class kept by this
 * repository. See {@code overrideECJ.sh} for how the patch is re-applied on every upgrade of ECJ.
 */
public final class SystemReleaseClasspath {

  /**
   * Compiler option selecting the Java release whose API must be visible to the parser, for instance {@code "11"}.
   * Not an official JDT option: it is read only by this class.
   */
  public static final String OPTION_RELEASE = "org.sonarsource.java.jdt.systemRelease";

  /**
   * Optional compiler option with the home directory of the JDK that provides {@code lib/ct.sym}.
   * When missing, the {@code java.home} system property is used.
   */
  public static final String OPTION_JAVA_HOME = "org.sonarsource.java.jdt.systemRelease.javaHome";

  /**
   * Oldest release served from {@code ct.sym}. Java 8 and older are deliberately left to an actual JDK of that version
   * (or its {@code rt.jar}) on the classpath: {@code ct.sym} only describes the supported public API, so for such releases
   * it hides APIs that the real JDK offered ({@code sun.misc.Unsafe}, {@code com.sun.*}), and ECJ does not resolve the
   * Java EE and CORBA modules that Java 8 shipped (for example {@code javax.xml.bind}) from it. For these releases
   * {@link #resolve(Map)} returns an empty result silently: it is a choice, not a problem of the JDK.
   */
  static final int MIN_RELEASE = 9;

  private static final System.Logger LOG = System.getLogger(SystemReleaseClasspath.class.getName());

  /**
   * (JDK, release) pairs already reported, so that a parser created for every file does not repeat the same warning.
   */
  private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

  private SystemReleaseClasspath() {
    // utility class
  }

  /**
   * Tells whether {@link #resolve(Map)} would substitute the API of a release, without keeping any classpath entry.
   * Clients use it to decide whether the JDK they were given can be replaced by the release API.
   * Logs the same warning as {@link #resolve(Map)} when the release was requested but cannot be served.
   *
   * @param options the compiler options of the {@link ASTParser}, may be {@code null}
   * @return {@code true} if a classpath entry for the requested release can be built from the selected JDK
   */
  public static boolean isApplicable(Map<String, String> options) {
    return resolve(options).isPresent();
  }

  /**
   * Builds the classpath entry exposing only the API of the release selected in {@code options}.
   *
   * @param options the compiler options of the {@link ASTParser}, may be {@code null}
   * @return the entry to use instead of the running-VM bootclasspath, or empty if the release API is not requested, or
   *         cannot be served by the selected JDK (a warning is logged in that case, see the class documentation)
   */
  static Optional<Classpath> resolve(Map<String, String> options) {
    if (options == null || options.get(OPTION_RELEASE) == null) {
      return Optional.empty();
    }
    String release = options.get(OPTION_RELEASE).trim();
    String explicitJavaHome = options.get(OPTION_JAVA_HOME);
    String javaHome = explicitJavaHome == null || explicitJavaHome.isEmpty() ? System.getProperty("java.home") : explicitJavaHome;

    if (isBelowMinimumRelease(release)) {
      LOG.log(System.Logger.Level.DEBUG, "The API of Java {0} is not served from ct.sym, the API of the running JVM is used instead.", release);
      return Optional.empty();
    }
    String problem = checkRelease(release);
    if (problem == null && javaHome == null) {
      problem = "the JDK is unknown";
    }
    if (problem == null && !Files.isRegularFile(Path.of(javaHome, "lib", "ct.sym"))) {
      problem = "it has no lib/ct.sym";
    }
    if (problem != null) {
      return fallback(javaHome, release, problem);
    }
    try {
      Classpath classpath = FileSystem.getOlderSystemRelease(javaHome, release, null);
      // Initializing now is what tells whether ct.sym describes this release: ECJ would otherwise fail later, during the parse
      classpath.initialize();
      return Optional.of(classpath);
    } catch (IOException | RuntimeException e) {
      return fallback(javaHome, release, e.getMessage() != null ? e.getMessage() : e.toString());
    }
  }

  private static boolean isBelowMinimumRelease(String release) {
    try {
      return Integer.parseInt(release) < MIN_RELEASE;
    } catch (NumberFormatException e) {
      return false; // reported by checkRelease
    }
  }

  private static String checkRelease(String release) {
    try {
      Integer.parseInt(release);
      return null;
    } catch (NumberFormatException e) {
      return "the release '" + release + "' is not a number";
    }
  }

  private static Optional<Classpath> fallback(String javaHome, String release, String problem) {
    if (REPORTED.add(javaHome + '|' + release)) {
      LOG.log(System.Logger.Level.WARNING,
        "The API of Java {0} cannot be served by the JDK ''{1}'' ({2}): the API of the running JVM is used instead.",
        release, javaHome, problem);
    }
    return Optional.empty();
  }

}
