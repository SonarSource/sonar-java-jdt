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
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jdt.internal.compiler.batch.ClasspathJep247;
import org.eclipse.jdt.internal.compiler.batch.ClasspathJrt;
import org.eclipse.jdt.internal.compiler.batch.FileSystem.Classpath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The release API is observed through two types of the JDK: {@code java.util.jar.Pack200}, which exists in Java 11 and
 * was removed in Java 14, and {@code java.lang.Record}, which was added in Java 16. The running JDK is used for it, and
 * fake JDK homes for the cases where there is no usable {@code ct.sym}.
 *
 * <p>These tests only load {@link SystemReleaseClasspath} from the {@code org.eclipse.jdt.core.dom} package. Loading
 * other classes of that package would mix them with the signed ones of the original JDT jar and fail with a
 * {@link SecurityException}, as explained in the pom.
 */
class SystemReleaseClasspathTest {

  private static final String JAVA_HOME = System.getProperty("java.home");

  @TempDir
  Path temp;

  @Test
  void running_vm_without_release_is_used_as_is() {
    assumeRunningJdkCanServeOlderReleases();

    assertThatIsRunningJdkApi(SystemReleaseClasspath.resolveTargetReleaseOfRunningVM(null));
    assertThatIsRunningJdkApi(SystemReleaseClasspath.resolveTargetReleaseOfRunningVM(Map.of()));
  }

  @Test
  void running_vm_exposes_only_the_api_of_the_requested_release() {
    assumeRunningJdkCanServeOlderReleases();

    assertThatIsApiOfJava11(SystemReleaseClasspath.resolveTargetReleaseOfRunningVM(release("11")));
    assertThatIsApiOfJava11(SystemReleaseClasspath.resolveTargetReleaseOfRunningVM(release(" 11 ")));
    assertThatIsApiOfJava17(SystemReleaseClasspath.resolveTargetReleaseOfRunningVM(release("17")));
  }

  @Test
  void running_vm_is_used_as_is_when_the_release_is_not_servable() {
    assumeRunningJdkCanServeOlderReleases();
    int feature = Runtime.version().feature();

    for (String release : List.of("8", "7", "1", Integer.toString(feature), Integer.toString(feature + 1), "latest", "")) {
      assertThatIsRunningJdkApi(SystemReleaseClasspath.resolveTargetReleaseOfRunningVM(release(release)));
    }
  }

  @Test
  void running_vm_ignores_the_java_home_option() {
    assumeRunningJdkCanServeOlderReleases();
    Map<String, String> options = release("11");
    options.put(SystemReleaseClasspath.OPTION_JAVA_HOME, temp.toString());

    assertThatIsApiOfJava11(SystemReleaseClasspath.resolveTargetReleaseOfRunningVM(options));
  }

  @Test
  void nothing_is_added_without_java_home() {
    assertThat(SystemReleaseClasspath.resolveTargetRelease(null)).isEmpty();
    assertThat(SystemReleaseClasspath.resolveTargetRelease(Map.of())).isEmpty();
    assertThat(SystemReleaseClasspath.resolveTargetRelease(release("11"))).isEmpty();
  }

  @Test
  void jdk_exposes_only_the_api_of_the_requested_release() {
    assumeRunningJdkCanServeOlderReleases();

    assertThatIsApiOfJava11(SystemReleaseClasspath.resolveTargetRelease(options("11", JAVA_HOME)));
    assertThatIsApiOfJava17(SystemReleaseClasspath.resolveTargetRelease(options("17", JAVA_HOME)));
  }

  @Test
  void jdk_is_used_as_is_without_release() {
    assumeRunningJdkCanServeOlderReleases();
    Map<String, String> options = new HashMap<>();
    options.put(SystemReleaseClasspath.OPTION_JAVA_HOME, JAVA_HOME);

    assertThatIsRunningJdkApi(SystemReleaseClasspath.resolveTargetRelease(options));
  }

  @Test
  void jdk_is_used_as_is_when_the_release_is_not_servable() {
    assumeRunningJdkCanServeOlderReleases();
    int feature = Runtime.version().feature();

    for (String release : List.of("8", "7", "1", Integer.toString(feature), Integer.toString(feature + 1), "latest", "")) {
      assertThatIsRunningJdkApi(SystemReleaseClasspath.resolveTargetRelease(options(release, JAVA_HOME)));
    }
  }

  @Test
  void jdk_without_ct_sym_is_used_as_is() throws IOException {
    assumeRunningJdkCanServeOlderReleases();

    // no ct.sym
    assertThatIsRunningJdkApi(SystemReleaseClasspath.resolveTargetRelease(options("11", fakeJdk("21.0.2", false))));
  }

  @Test
  void jdk_version_is_read_from_its_release_file() throws IOException {
    assumeRunningJdkCanServeOlderReleases();

    // 11 is not older than the version of the JDK, whatever the content of its ct.sym is
    for (String version : List.of("11.0.2", "11", "11-ea", "1.11")) {
      assertThatIsRunningJdkApi(SystemReleaseClasspath.resolveTargetRelease(options("11", fakeJdk(version, true))));
    }
  }

  @Test
  void jdk_is_used_as_is_when_ecj_cannot_open_the_release() throws IOException {
    assumeRunningJdkCanServeOlderReleases();

    // the ct.sym is not a valid archive
    assertThatIsRunningJdkApi(SystemReleaseClasspath.resolveTargetRelease(options("11", fakeJdk("21.0.2", true))));
  }

  @Test
  void jdk_is_used_as_is_for_a_release_that_ct_sym_does_not_describe() throws IOException {
    assumeRunningJdkCanServeOlderReleases();

    // the release file of a JDK 99 on top of the ct.sym of the running one
    assertThatIsRunningJdkApi(SystemReleaseClasspath.resolveTargetRelease(options("98", fakeJdkWithRunningCtSym("99"))));
  }

  private static void assumeRunningJdkCanServeOlderReleases() {
    assumeTrue(new File(JAVA_HOME, "lib/ct.sym").isFile(), "the running JVM has no lib/ct.sym");
    assumeTrue(new File(JAVA_HOME, "release").isFile(), "the running JVM has no release file");
    assumeTrue(Runtime.version().feature() > 17, "the running JVM must be newer than Java 17");
  }

  private static void assertThatIsApiOfJava11(List<Classpath> classpath) {
    assertThatIsServedFromCtSym(classpath, "11");
    assertThat(declares(classpath, "java/util/jar", "Pack200")).as("Pack200 exists in 11").isTrue();
    assertThat(declares(classpath, "java/lang", "Record")).as("Record was added in 16").isFalse();
    assertThat(declares(classpath, "java/lang", "String")).isTrue();
  }

  private static void assertThatIsApiOfJava17(List<Classpath> classpath) {
    assertThatIsServedFromCtSym(classpath, "17");
    assertThat(declares(classpath, "java/util/jar", "Pack200")).as("Pack200 was removed in 14").isFalse();
    assertThat(declares(classpath, "java/lang", "Record")).isTrue();
  }

  /**
   * ECJ serves an older release from {@code ct.sym} with a {@link ClasspathJep247}, which remembers the release it was
   * created for.
   */
  private static void assertThatIsServedFromCtSym(List<Classpath> classpath, String expectedRelease) {
    assertThat(classpath).hasSize(1);
    assertThat(classpath.get(0)).isInstanceOf(ClasspathJep247.class);
    assertThat(releaseOf((ClasspathJep247) classpath.get(0))).isEqualTo(expectedRelease);
  }

  /**
   * Without a release to serve, ECJ reads the runtime image of the JDK, with {@link ClasspathJrt} entries only: none
   * of them is bound to a release.
   */
  private static void assertThatIsRunningJdkApi(List<Classpath> classpath) {
    assertThat(classpath).isNotEmpty().allSatisfy(entry -> assertThat(entry)
      .isInstanceOf(ClasspathJrt.class)
      .isNotInstanceOf(ClasspathJep247.class));
    assertThat(declares(classpath, "java/lang", "Record")).isTrue();
    assertThat(declares(classpath, "java/lang", "String")).isTrue();
  }

  private static String releaseOf(ClasspathJep247 classpath) {
    try {
      // not exposed by ECJ
      Field compliance = ClasspathJep247.class.getDeclaredField("compliance");
      compliance.setAccessible(true);
      return (String) compliance.get(classpath);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("ECJ no longer keeps the release of a ClasspathJep247 in 'compliance'", e);
    }
  }

  private static boolean declares(List<Classpath> classpath, String packageName, String simpleName) {
    return classpath.stream().anyMatch(entry -> {
      try {
        entry.initialize();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return entry.findClass(simpleName.toCharArray(), packageName, "java.base", packageName + "/" + simpleName + ".class") != null;
    });
  }

  private static Map<String, String> release(String release) {
    Map<String, String> options = new HashMap<>();
    options.put(SystemReleaseClasspath.OPTION_RELEASE, release);
    return options;
  }

  private static Map<String, String> options(String release, String javaHome) {
    Map<String, String> options = release(release);
    options.put(SystemReleaseClasspath.OPTION_JAVA_HOME, javaHome);
    return options;
  }

  /**
   * A JDK home sharing the runtime image of the running JDK, with a {@code release} file saying it is {@code version}
   * (none if {@code null}). When {@code withCtSym} is set, the {@code lib/ct.sym} file exists but is not a valid archive.
   */
  private String fakeJdk(String version, boolean withCtSym) throws IOException {
    Path home = Files.createTempDirectory(temp, "jdk-");
    Path lib = Files.createDirectories(home.resolve("lib"));
    Files.createSymbolicLink(lib.resolve("jrt-fs.jar"), Path.of(JAVA_HOME, "lib", "jrt-fs.jar"));
    Files.createSymbolicLink(lib.resolve("modules"), Path.of(JAVA_HOME, "lib", "modules"));
    if (version != null) {
      Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"x\"\nJAVA_VERSION=\"" + version + "\"\n");
    }
    if (withCtSym) {
      Files.writeString(lib.resolve("ct.sym"), "this is not a zip archive");
    }
    return home.toString();
  }

  private String fakeJdkWithRunningCtSym(String version) throws IOException {
    String home = fakeJdk(version, false);
    Files.createSymbolicLink(Path.of(home, "lib", "ct.sym"), Path.of(JAVA_HOME, "lib", "ct.sym"));
    return home;
  }

}
