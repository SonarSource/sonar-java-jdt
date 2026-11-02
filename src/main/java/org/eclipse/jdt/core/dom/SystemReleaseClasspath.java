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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.eclipse.jdt.internal.compiler.batch.FileSystem;
import org.eclipse.jdt.internal.compiler.batch.FileSystem.Classpath;

/**
 * Lets {@link ASTParser} see the Java API of an older Java release while running on a newer JDK.
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
 *   <li>a use of an API added after 11 (for example {@code java.lang.StackWalker}, added in 9, when targeting 8) resolves
 *   successfully, although the project could not compile;</li>
 *   <li>a use of an API removed before 26 (for example {@code javax.security.auth.Policy} or
 *   {@code java.util.jar.Pack200}) is reported as an unresolved type, although it is valid for the project.</li>
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
 * When both the option is present and the running-VM bootclasspath was requested, {@code ASTParser#getClasspath()}
 * uses the entry returned by {@link #resolve(Map)} <em>in place of</em> the running-VM bootclasspath. Everything else
 * is untouched: source paths and the project's own jars are added exactly as before, and the real
 * {@code ICompilationUnitResolver} is still used. Since both the single-file ({@code createAST}) and the batch
 * ({@code createASTs}) code paths build their classpath through {@code getClasspath()}, both are covered.
 *
 * <h2>When it deliberately does nothing</h2>
 * {@link #resolve(Map)} returns an empty result, and {@link #isApplicable(Map)} returns {@code false}, so that the parser
 * keeps its previous behavior, when:
 * <ul>
 *   <li>{@link #OPTION_RELEASE} is absent or is not a number;</li>
 *   <li>the release is older than {@value #MIN_RELEASE_USING_CT_SYM}: for such releases the analyzer needs an actual JDK
 *   (or {@code rt.jar}) of that version on the classpath, in which case the running-VM bootclasspath is not requested
 *   in the first place;</li>
 *   <li>the release is not older than the version of the JDK that would provide {@code ct.sym}: that JDK already is the
 *   requested API, and asking its {@code ct.sym} for a release it does not describe would resolve nothing;</li>
 *   <li>the version of that JDK cannot be determined, or it has no {@code lib/ct.sym}, as is the case for some JREs and
 *   custom {@code jlink} images.</li>
 * </ul>
 *
 * <h2>Which JDK provides {@code ct.sym}</h2>
 * The JDK given by {@link #OPTION_JAVA_HOME}, or the running JVM when the option is missing. This lets a client use the
 * JDK that the user configured (for example through {@code sonar.java.jdkHome}) even when the analysis itself runs on a
 * JRE that does not ship {@code ct.sym}. The version of that JDK is read from the {@code JAVA_VERSION} entry of its
 * {@code release} file, and from {@link Runtime#version()} for the running JVM when that file is missing.
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
   * The release of that JDK must be greater than {@link #OPTION_RELEASE} for the substitution to apply.
   */
  public static final String OPTION_JAVA_HOME = "org.sonarsource.java.jdt.systemRelease.javaHome";

  /**
   * First release described by {@code ct.sym} that this class relies on. Older releases have no module
   * information in {@code ct.sym}, and are expected to be analyzed against an actual JDK of that version.
   */
  static final int MIN_RELEASE_USING_CT_SYM = 9;

  private static final String CT_SYM = "lib" + File.separator + "ct.sym";

  private SystemReleaseClasspath() {
    // utility class
  }

  /**
   * Tells whether {@link #resolve(Map)} would substitute the API of a release, without creating any classpath entry.
   * Clients use it to decide whether the JDK they were given can be replaced by the release API.
   *
   * @param options the compiler options of the {@link ASTParser}, may be {@code null}
   * @return {@code true} if a classpath entry for the requested release can be built from the selected JDK
   */
  public static boolean isApplicable(Map<String, String> options) {
    return plan(options).isPresent();
  }

  /**
   * Computes the classpath entry exposing only the API of the release selected in {@code options}.
   *
   * @param options the compiler options of the {@link ASTParser}, may be {@code null}
   * @return the entry to use instead of the running-VM bootclasspath, or empty if the release API must not be
   *         substituted (see the class documentation for the cases)
   */
  static Optional<Classpath> resolve(Map<String, String> options) {
    return plan(options).flatMap(plan -> Optional.ofNullable(FileSystem.getOlderSystemRelease(plan.javaHome, plan.release, null)));
  }

  private static Optional<Plan> plan(Map<String, String> options) {
    if (options == null) {
      return Optional.empty();
    }
    OptionalInt release = parseRelease(options.get(OPTION_RELEASE));
    if (release.isEmpty() || release.getAsInt() < MIN_RELEASE_USING_CT_SYM) {
      return Optional.empty();
    }
    String explicitJavaHome = options.get(OPTION_JAVA_HOME);
    boolean usesRunningJdk = explicitJavaHome == null || explicitJavaHome.isEmpty();
    String javaHome = usesRunningJdk ? System.getProperty("java.home") : explicitJavaHome;
    if (javaHome == null || !new File(javaHome, CT_SYM).isFile()) {
      return Optional.empty();
    }
    OptionalInt jdkVersion = jdkFeatureVersion(javaHome, usesRunningJdk);
    if (jdkVersion.isEmpty() || release.getAsInt() >= jdkVersion.getAsInt()) {
      return Optional.empty();
    }
    return Optional.of(new Plan(javaHome, Integer.toString(release.getAsInt())));
  }

  private static OptionalInt parseRelease(String text) {
    if (text == null) {
      return OptionalInt.empty();
    }
    try {
      return OptionalInt.of(Integer.parseInt(text.trim()));
    } catch (NumberFormatException e) {
      return OptionalInt.empty();
    }
  }

  /**
   * Reads the feature version (8, 11, 21...) of a JDK from the {@code JAVA_VERSION} entry of its {@code release} file.
   * Both the legacy {@code 1.8.0_362} and the current {@code 21.0.2} notations are supported.
   */
  private static OptionalInt jdkFeatureVersion(String javaHome, boolean runningJdk) {
    Path releaseFile = Path.of(javaHome, "release");
    if (Files.isRegularFile(releaseFile)) {
      try (Stream<String> lines = Files.lines(releaseFile)) {
        Optional<OptionalInt> fromFile = lines
          .map(String::trim)
          .filter(line -> line.startsWith("JAVA_VERSION="))
          .map(line -> parseFeatureVersion(line.substring("JAVA_VERSION=".length())))
          .findFirst();
        if (fromFile.isPresent()) {
          return fromFile.get();
        }
      } catch (IOException | UncheckedIOException e) {
        return runningJdk ? OptionalInt.of(Runtime.version().feature()) : OptionalInt.empty();
      }
    }
    return runningJdk ? OptionalInt.of(Runtime.version().feature()) : OptionalInt.empty();
  }

  private static OptionalInt parseFeatureVersion(String rawValue) {
    String value = rawValue.replace("\"", "").trim();
    String[] parts = value.split("[._\\-+]");
    try {
      int first = Integer.parseInt(parts[0]);
      if (first == 1 && parts.length > 1) {
        return OptionalInt.of(Integer.parseInt(parts[1]));
      }
      return OptionalInt.of(first);
    } catch (NumberFormatException e) {
      return OptionalInt.empty();
    }
  }

  private record Plan(String javaHome, String release) {
  }

}
