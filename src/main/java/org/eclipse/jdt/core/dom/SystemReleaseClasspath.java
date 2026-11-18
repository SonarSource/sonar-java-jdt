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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eclipse.jdt.internal.compiler.batch.FileSystem;
import org.eclipse.jdt.internal.compiler.batch.FileSystem.Classpath;
import org.eclipse.jdt.internal.compiler.util.Util;

/**
 * Gives the {@link ASTParser} the platform classes of a JDK, exposing only the API of a given Java release when asked
 * to (the {@code --release} flag of {@code javac}, JEP 247), as served by the {@code lib/ct.sym} of that JDK.
 *
 * <p>Two options drive it, and both are set by the client of the parser:
 * <ul>
 *   <li>{@link #OPTION_RELEASE}: the release whose API must be visible;</li>
 *   <li>{@link #OPTION_JAVA_HOME}: the home of the modular JDK to read the platform classes from. It is set only when
 *   the parser must not include the running VM.</li>
 * </ul>
 *
 * <h2>Running VM</h2>
 * When the parser includes the running VM ({@link #resolveTargetReleaseOfRunningVM(Map)}), the running JDK is the
 * source of the platform classes.
 *
 * <h2>JDK given by the client</h2>
 * When it does not ({@link #resolveTargetRelease(Map)}), nothing is added unless {@link #OPTION_JAVA_HOME} is set. A
 * missing home means that the classpath already holds a non-modular JDK ({@code rt.jar}, {@code android.jar}), which is
 * the actual platform to analyze against.
 *
 * <h2>When the release is used</h2>
 * The release API is served from {@code ct.sym} only if {@link #OPTION_RELEASE} is a number which is at least
 * {@value #MIN_RELEASE} and older than the JDK, and the JDK has a {@code lib/ct.sym}. Releases 8 and older are left to
 * an actual JDK of that version: {@code ct.sym} only describes the supported public API, so it hides APIs that the real
 * JDK offered ({@code sun.misc.Unsafe}, {@code com.sun.*}), and ECJ does not resolve the Java EE and CORBA modules that
 * Java 8 shipped from it. A JDK does not describe its own release in {@code ct.sym} either: its own API already is the
 * one of that release. In all these cases the JDK is used as is, silently.
 *
 * <p>If the release should be served but ECJ cannot open it, for instance because {@code ct.sym} is corrupt, an
 * {@link IllegalStateException} is thrown.
 */
public final class SystemReleaseClasspath {

  /**
   * Compiler option selecting the Java release whose API must be visible to the parser, for instance {@code "11"}.
   * Not an official JDT option: it is read only by this class.
   */
  public static final String OPTION_RELEASE = "org.sonarsource.java.jdt.systemRelease";

  /**
   * Compiler option with the home directory of the modular JDK to read the platform classes from, when the parser does
   * not include the running VM. Not an official JDT option: it is read only by this class.
   */
  public static final String OPTION_JAVA_HOME = "org.sonarsource.java.jdt.systemRelease.javaHome";

  /**
   * Oldest release served from {@code ct.sym}, see the class documentation.
   */
  private static final int MIN_RELEASE = 9;

  private static final Pattern JAVA_VERSION_LINE = Pattern.compile("JAVA_VERSION=\"?(?:1\\.(\\d+)|(\\d+))[^\"]*\"?\\s*");

  private SystemReleaseClasspath() {
    // utility class
  }

  /**
   * The platform classes of the running VM: the API of the release requested in {@code options} if the running JDK can
   * serve it, the whole API of the running JDK otherwise.
   *
   * @throws IllegalStateException if the release should be served but ECJ cannot open it
   */
  static List<Classpath> resolveTargetReleaseOfRunningVM(Map<String, String> options) {
    String javaHome = System.getProperty("java.home");
    Optional<Classpath> release = targetRelease(options, javaHome, Runtime.version().feature());
    if (release.isPresent()) {
      return List.of(release.get());
    }
    List<Classpath> runningVM = new ArrayList<>();
    Util.collectRunningVMBootclasspath(runningVM);
    return runningVM;
  }

  /**
   * The platform classes of the JDK at {@link #OPTION_JAVA_HOME}: the API of the release requested in {@code options}
   * if that JDK can serve it, the whole API of that JDK otherwise. Nothing if there is no such option.
   *
   * @throws IllegalStateException if the release should be served but ECJ cannot open it
   */
  static List<Classpath> resolveTargetRelease(Map<String, String> options) {
    String javaHome = options == null ? null : options.get(OPTION_JAVA_HOME);
    if (javaHome == null) {
      return List.of();
    }
    Optional<Classpath> release = jdkFeatureVersion(javaHome).flatMap(feature -> targetRelease(options, javaHome, feature));
    return List.of(release.orElseGet(() -> FileSystem.getJrtClasspath(javaHome, null, null, null)));
  }

  private static Optional<Classpath> targetRelease(Map<String, String> options, String javaHome, int jdkFeatureVersion) {
    Optional<Integer> release = requestedRelease(options);
    if (release.isEmpty() || release.get() < MIN_RELEASE || release.get() >= jdkFeatureVersion
      || !Files.isRegularFile(Path.of(javaHome, "lib", "ct.sym"))) {
      return Optional.empty();
    }
    return Optional.of(getInitializedClasspath(javaHome, release.get().toString()));
  }

  private static Optional<Integer> requestedRelease(Map<String, String> options) {
    String release = options == null ? null : options.get(OPTION_RELEASE);
    if (release == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(Integer.parseInt(release.trim()));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private static Classpath getInitializedClasspath(String javaHome, String release) {
    try {
      Classpath classpath = FileSystem.getOlderSystemRelease(javaHome, release, null);
      // Initializing now is what tells whether ct.sym describes this release: ECJ would otherwise fail later, during the parse
      classpath.initialize();
      return classpath;
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException("The API of Java %s cannot be served by the JDK %s: %s".formatted(
        release, javaHome, e.getMessage() != null ? e.getMessage() : e.toString()), e);
    }
  }

  private static Optional<Integer> jdkFeatureVersion(String javaHome) {
    Path releaseFile = Path.of(javaHome, "release");
    if (!Files.isRegularFile(releaseFile)) {
      return Optional.empty();
    }
    try (Stream<String> lines = Files.lines(releaseFile)) {
      return lines
        .map(JAVA_VERSION_LINE::matcher)
        .filter(Matcher::matches)
        .findFirst()
        .map(m -> Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2)));
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

}
