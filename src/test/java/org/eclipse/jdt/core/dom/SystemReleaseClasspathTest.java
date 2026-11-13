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
package org.eclipse.jdt.core.dom;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.eclipse.jdt.internal.compiler.batch.FileSystem.Classpath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Whether a release can be served is decided by the {@code ct.sym} of the JDK, as ECJ reads it. The cases where there is
 * no usable {@code ct.sym} are tested on fake JDK homes. The others use the running JDK, and check that the API which is
 * actually exposed is the one of the requested release.
 *
 * <p>These tests only load {@link SystemReleaseClasspath} from the {@code org.eclipse.jdt.core.dom} package. Loading
 * other classes of that package would mix them with the signed ones of the original JDT jar and fail with a
 * {@link SecurityException}, as explained in the pom.
 */
class SystemReleaseClasspathTest {

  private static final String LOGGER_NAME = SystemReleaseClasspath.class.getName();

  @TempDir
  Path temp;

  private final List<String> warnings = new ArrayList<>();
  private final Handler handler = new Handler() {
    @Override
    public void publish(LogRecord record) {
      if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
        warnings.add(new java.text.MessageFormat(record.getMessage()).format(record.getParameters()));
      }
    }

    @Override
    public void flush() {
      // nothing to flush
    }

    @Override
    public void close() {
      // nothing to close
    }
  };

  // java.util.logging only keeps weak references to its loggers: this one must stay reachable for the handler to stay attached
  private java.util.logging.Logger logger;

  @BeforeEach
  void captureWarnings() {
    logger = java.util.logging.Logger.getLogger(LOGGER_NAME);
    logger.addHandler(handler);
  }

  @AfterEach
  void stopCapturingWarnings() {
    logger.removeHandler(handler);
  }

  @Test
  void is_not_applicable_without_the_release_option_and_says_nothing() {
    assertThat(SystemReleaseClasspath.isApplicable(null)).isFalse();
    assertThat(SystemReleaseClasspath.isApplicable(Map.of())).isFalse();
    assertThat(SystemReleaseClasspath.isApplicable(Map.of(SystemReleaseClasspath.OPTION_JAVA_HOME, System.getProperty("java.home")))).isFalse();
    assertThat(SystemReleaseClasspath.resolve(null)).isEmpty();

    assertThat(warnings).isEmpty();
  }

  @Test
  void falls_back_with_a_warning_when_release_is_not_a_number() throws IOException {
    String home = fakeJdk(true);

    assertThat(isApplicable("latest", home)).isFalse();
    assertThat(isApplicable("", home)).isFalse();
    assertThat(isApplicable("11.0", home)).isFalse();

    assertThat(warnings).hasSize(3);
    assertThat(warnings.get(0)).contains("'latest' is not a number");
  }

  @Test
  void falls_back_with_a_warning_without_ct_sym() throws IOException {
    String home = fakeJdk(false);

    assertThat(isApplicable("11", home)).isFalse();

    assertThat(warnings).singleElement().satisfies(warning -> assertThat(warning)
      .contains("Java 11")
      .contains(home)
      .contains("no lib/ct.sym")
      .contains("API of the running JVM is used instead"));
  }

  @Test
  void falls_back_with_a_warning_when_the_home_does_not_exist() {
    assertThat(isApplicable("11", temp.resolve("missing").toString())).isFalse();

    assertThat(warnings).singleElement().satisfies(warning -> assertThat(warning).contains("no lib/ct.sym"));
  }

  @Test
  void falls_back_with_a_warning_when_ct_sym_cannot_be_read() throws IOException {
    String home = fakeJdk(true);

    assertThat(isApplicable("11", home)).isFalse();
    assertThat(SystemReleaseClasspath.resolve(options("11", home))).isEmpty();

    assertThat(warnings).hasSize(1);
  }

  @Test
  void warns_once_per_jdk_and_release() throws IOException {
    String home = fakeJdk(false);
    String otherHome = fakeJdk(false);

    for (int i = 0; i < 5; i++) {
      isApplicable("11", home);
    }
    assertThat(warnings).hasSize(1);

    isApplicable("17", home);
    isApplicable("11", otherHome);
    assertThat(warnings).hasSize(3);
  }

  @Test
  void release_is_trimmed() {
    assumeRunningJdkCanServeOlderReleases();

    assertThat(SystemReleaseClasspath.isApplicable(Map.of(SystemReleaseClasspath.OPTION_RELEASE, " 11 "))).isTrue();
    assertThat(warnings).isEmpty();
  }

  @Test
  void empty_java_home_option_means_running_jvm() {
    assumeRunningJdkCanServeOlderReleases();
    Map<String, String> options = new HashMap<>();
    options.put(SystemReleaseClasspath.OPTION_RELEASE, "11");
    options.put(SystemReleaseClasspath.OPTION_JAVA_HOME, "");

    assertThat(SystemReleaseClasspath.isApplicable(options)).isTrue();
  }

  @Test
  void running_jvm_serves_every_release_its_ct_sym_describes() {
    assumeRunningJdkCanServeOlderReleases();
    int feature = Runtime.version().feature();

    assertThat(isApplicableOnRunningJvm("9")).isTrue();
    assertThat(isApplicableOnRunningJvm("11")).isTrue();
    assertThat(isApplicableOnRunningJvm(Integer.toString(feature - 1))).isTrue();
    assertThat(warnings).isEmpty();
  }

  /**
   * ECJ cannot serve from {@code ct.sym} the release of the JDK that owns it: that release has no module information
   * there, and ECJ fails to initialize the entry. Looking at it up front is what avoids a failure during the parse.
   */
  @Test
  void running_jvm_falls_back_with_a_warning_for_its_own_release() {
    assumeRunningJdkCanServeOlderReleases();
    int feature = Runtime.version().feature();

    assertThat(isApplicableOnRunningJvm(Integer.toString(feature))).isFalse();

    assertThat(warnings).singleElement().satisfies(warning -> assertThat(warning).contains("Java " + feature));
  }

  @Test
  void running_jvm_falls_back_with_a_warning_for_releases_that_ct_sym_does_not_describe() {
    assumeRunningJdkCanServeOlderReleases();
    int newer = Runtime.version().feature() + 3;

    assertThat(isApplicableOnRunningJvm(Integer.toString(newer))).isFalse();

    assertThat(warnings).singleElement().satisfies(warning -> assertThat(warning).contains("Java " + newer));
  }

  /**
   * Java 8 and older are left to an actual JDK of that version, see {@link SystemReleaseClasspath#MIN_RELEASE}. It is a
   * choice and not a problem of the JDK, so it is neither a warning nor dependent on the JDK: a fake one is enough.
   */
  @Test
  void java_8_and_older_are_never_served_and_say_nothing() throws IOException {
    assertThat(isApplicable("8", fakeJdk(true))).isFalse();
    assertThat(isApplicable("7", fakeJdk(true))).isFalse();
    assertThat(isApplicable("1", fakeJdk(false))).isFalse();
    assertThat(isApplicableOnRunningJvm("8")).isFalse();
    assertThat(SystemReleaseClasspath.resolve(Map.of(SystemReleaseClasspath.OPTION_RELEASE, "8"))).isEmpty();

    assertThat(warnings).isEmpty();
  }

  @Test
  void the_oldest_release_served_is_java_9() {
    assumeRunningJdkCanServeOlderReleases();

    assertThat(SystemReleaseClasspath.MIN_RELEASE).isEqualTo(9);
    assertThat(isApplicableOnRunningJvm("9")).isTrue();
  }

  @Test
  void resolved_classpath_exposes_only_the_api_of_the_requested_release() {
    assumeRunningJdkCanServeOlderReleases();

    Optional<Classpath> release11 = SystemReleaseClasspath.resolve(Map.of(SystemReleaseClasspath.OPTION_RELEASE, "11"));
    Optional<Classpath> release17 = SystemReleaseClasspath.resolve(Map.of(SystemReleaseClasspath.OPTION_RELEASE, "17"));

    assertThat(release11).isPresent();
    assertThat(release17).isPresent();
    assertThat(declares(release11.get(), "java/util/jar", "Pack200")).as("Pack200 exists in 11").isTrue();
    assertThat(declares(release17.get(), "java/util/jar", "Pack200")).as("Pack200 was removed in 14").isFalse();
    assertThat(declares(release11.get(), "java/lang", "Record")).as("Record was added in 16").isFalse();
    assertThat(declares(release17.get(), "java/lang", "Record")).isTrue();
    assertThat(declares(release11.get(), "java/lang", "String")).isTrue();
  }

  @Test
  void resolved_classpath_can_come_from_an_explicit_jdk() {
    assumeRunningJdkCanServeOlderReleases();

    Optional<Classpath> release11 = SystemReleaseClasspath.resolve(options("11", System.getProperty("java.home")));

    assertThat(release11).isPresent();
    assertThat(declares(release11.get(), "java/util/jar", "Pack200")).isTrue();
  }

  private static boolean isApplicableOnRunningJvm(String release) {
    return SystemReleaseClasspath.isApplicable(Map.of(SystemReleaseClasspath.OPTION_RELEASE, release));
  }

  private static void assumeRunningJdkCanServeOlderReleases() {
    assumeTrue(new File(System.getProperty("java.home"), "lib/ct.sym").isFile(), "the running JVM has no lib/ct.sym");
    assumeTrue(Runtime.version().feature() > 17, "the running JVM must be newer than Java 17");
  }

  private static boolean declares(Classpath classpath, String packageName, String simpleName) {
    initialize(classpath);
    return classpath.findClass(simpleName.toCharArray(), packageName, "java.base", packageName + "/" + simpleName + ".class") != null;
  }

  private static void initialize(Classpath classpath) {
    try {
      classpath.initialize();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean isApplicable(String release, String javaHome) {
    return SystemReleaseClasspath.isApplicable(options(release, javaHome));
  }

  private static Map<String, String> options(String release, String javaHome) {
    Map<String, String> options = new HashMap<>();
    options.put(SystemReleaseClasspath.OPTION_RELEASE, release);
    options.put(SystemReleaseClasspath.OPTION_JAVA_HOME, javaHome);
    return options;
  }

  /**
   * A JDK home, different for every call so that the warning of one test does not hide the one of another. When
   * {@code withCtSym} is set, the {@code lib/ct.sym} file exists but is not a valid archive.
   */
  private String fakeJdk(boolean withCtSym) throws IOException {
    Path home = Files.createTempDirectory(temp, "jdk-");
    Path lib = Files.createDirectories(home.resolve("lib"));
    if (withCtSym) {
      Files.writeString(lib.resolve("ct.sym"), "this is not a zip archive");
    }
    return home.toString();
  }

}
