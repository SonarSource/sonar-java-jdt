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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jdt.internal.compiler.batch.FileSystem.Classpath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The guard logic is tested on fake JDK homes: only the {@code release} file and the presence of {@code lib/ct.sym} are
 * read before a classpath entry is created. A last group of tests uses the running JDK to check that the API which is
 * actually exposed is the one of the requested release.
 *
 * <p>These tests only load {@link SystemReleaseClasspath} from the {@code org.eclipse.jdt.core.dom} package. Loading
 * other classes of that package would mix them with the signed ones of the original JDT jar and fail with a
 * {@link SecurityException}, as explained in the pom.
 */
class SystemReleaseClasspathTest {

  @TempDir
  Path temp;

  @Test
  void is_not_applicable_without_options() {
    assertThat(SystemReleaseClasspath.isApplicable(null)).isFalse();
    assertThat(SystemReleaseClasspath.isApplicable(Map.of())).isFalse();
    assertThat(SystemReleaseClasspath.resolve(null)).isEmpty();
  }

  @Test
  void is_not_applicable_when_release_is_not_a_number() throws IOException {
    String home = fakeJdk("21.0.2", true);
    assertThat(isApplicable("latest", home)).isFalse();
    assertThat(isApplicable("", home)).isFalse();
    assertThat(isApplicable("11.0", home)).isFalse();
  }

  @Test
  void is_not_applicable_below_release_9() throws IOException {
    String home = fakeJdk("21.0.2", true);
    assertThat(isApplicable("8", home)).isFalse();
    assertThat(isApplicable("7", home)).isFalse();
    assertThat(isApplicable("9", home)).isTrue();
  }

  @Test
  void is_applicable_only_for_releases_older_than_the_jdk() throws IOException {
    String home = fakeJdk("21.0.2", true);
    assertThat(isApplicable("9", home)).isTrue();
    assertThat(isApplicable("11", home)).isTrue();
    assertThat(isApplicable("20", home)).isTrue();
    assertThat(isApplicable("21", home)).isFalse();
    assertThat(isApplicable("22", home)).isFalse();
    assertThat(isApplicable("26", home)).isFalse();
  }

  @Test
  void release_is_trimmed() throws IOException {
    assertThat(isApplicable(" 11 ", fakeJdk("21.0.2", true))).isTrue();
  }

  @Test
  void is_not_applicable_without_ct_sym() throws IOException {
    assertThat(isApplicable("11", fakeJdk("21.0.2", false))).isFalse();
  }

  @Test
  void is_not_applicable_when_the_home_does_not_exist() {
    assertThat(isApplicable("11", temp.resolve("missing").toString())).isFalse();
  }

  @Test
  void is_not_applicable_when_the_version_of_the_jdk_is_unknown() throws IOException {
    Path withoutReleaseFile = jdkHome("no-release-file", true);
    assertThat(isApplicable("11", withoutReleaseFile.toString())).isFalse();

    Path withoutVersion = jdkHome("no-version", true);
    Files.writeString(withoutVersion.resolve("release"), "IMPLEMENTOR=\"Someone\"\n");
    assertThat(isApplicable("11", withoutVersion.toString())).isFalse();

    Path withGarbageVersion = jdkHome("garbage-version", true);
    Files.writeString(withGarbageVersion.resolve("release"), "JAVA_VERSION=\"abc\"\n");
    assertThat(isApplicable("11", withGarbageVersion.toString())).isFalse();
  }

  @Test
  void reads_the_version_among_other_entries_of_the_release_file() throws IOException {
    Path home = jdkHome("many-entries", true);
    Files.writeString(home.resolve("release"), """
      IMPLEMENTOR="Eclipse Adoptium"
      JAVA_RUNTIME_VERSION="17.0.9+9"
      JAVA_VERSION="17.0.9"
      OS_NAME="Linux"
      """);
    assertThat(isApplicable("11", home.toString())).isTrue();
    assertThat(isApplicable("17", home.toString())).isFalse();
  }

  @Test
  void supports_the_notations_of_the_version() throws IOException {
    assertThat(isApplicable("9", fakeJdk("1.8.0_362", true))).isFalse();
    assertThat(isApplicable("9", fakeJdk("9", true))).isFalse();
    assertThat(isApplicable("9", fakeJdk("10", true))).isTrue();
    assertThat(isApplicable("11", fakeJdk("17-ea", true))).isTrue();
    assertThat(isApplicable("17", fakeJdk("17-ea", true))).isFalse();
    assertThat(isApplicable("25", fakeJdk("26+13", true))).isTrue();
  }

  @Test
  void empty_java_home_option_means_running_jvm() {
    Map<String, String> options = new HashMap<>();
    options.put(SystemReleaseClasspath.OPTION_RELEASE, "11");
    options.put(SystemReleaseClasspath.OPTION_JAVA_HOME, "");
    assertThat(SystemReleaseClasspath.isApplicable(options)).isEqualTo(runningJdkHasCtSym());
  }

  @Test
  void running_jvm_is_used_without_java_home_option() {
    int feature = Runtime.version().feature();
    assertThat(SystemReleaseClasspath.isApplicable(Map.of(SystemReleaseClasspath.OPTION_RELEASE, Integer.toString(feature))))
      .isFalse();
    assertThat(SystemReleaseClasspath.isApplicable(Map.of(SystemReleaseClasspath.OPTION_RELEASE, Integer.toString(feature + 1))))
      .isFalse();
    assertThat(SystemReleaseClasspath.isApplicable(Map.of(SystemReleaseClasspath.OPTION_RELEASE, "11")))
      .isEqualTo(runningJdkHasCtSym());
  }

  @Test
  void resolve_matches_is_applicable() throws IOException {
    String home = fakeJdk("21.0.2", false);
    assertThat(SystemReleaseClasspath.resolve(options("11", home))).isEmpty();
    assertThat(SystemReleaseClasspath.resolve(options("21", fakeJdk("21.0.2", true)))).isEmpty();
  }

  @Test
  void resolved_classpath_exposes_only_the_api_of_the_requested_release() {
    assumeTrue(runningJdkHasCtSym() && Runtime.version().feature() > 17);

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
    String home = System.getProperty("java.home");
    assumeTrue(runningJdkHasCtSym() && Runtime.version().feature() > 11);

    Optional<Classpath> release11 = SystemReleaseClasspath.resolve(options("11", home));

    assertThat(release11).isPresent();
    assertThat(declares(release11.get(), "java/util/jar", "Pack200")).isTrue();
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

  private static boolean runningJdkHasCtSym() {
    return new File(System.getProperty("java.home"), "lib/ct.sym").isFile();
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

  private String fakeJdk(String version, boolean withCtSym) throws IOException {
    Path home = jdkHome(Files.createTempDirectory(temp, "jdk-").getFileName().toString(), withCtSym);
    Files.writeString(home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
    return home.toString();
  }

  private Path jdkHome(String name, boolean withCtSym) throws IOException {
    Path home = temp.resolve(name);
    Path lib = Files.createDirectories(home.resolve("lib"));
    if (withCtSym) {
      Files.createFile(lib.resolve("ct.sym"));
    }
    return home;
  }

}
