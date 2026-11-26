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
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class EclipseLicenseHeaderTest {

  private static final String ECLIPSE_HEADER = """
    /*******************************************************************************
     * Copyright (c) 2000, 2016 IBM Corporation and others.
     *
     * This program and the accompanying materials
     * are made available under the terms of the Eclipse Public License 2.0
     * which accompanies this distribution, and is available at
     * https://www.eclipse.org/legal/epl-2.0/
     *
     * SPDX-License-Identifier: EPL-2.0
     *******************************************************************************/
    """;

  private static final String SONAR_HEADER = """
    /*
     * Eclipse JDT Core Package
     * Copyright (C) SonarSource Sàrl
     * the Sonar Source-Available License Version 1
     */
    """;

  @TempDir
  Path tmp;

  /** The real check: it fails the build when a file of src/main/java breaks the header rules. */
  @Test
  void sources_respect_eclipse_license_rules() throws IOException {
    Path sources = Path.of("src/main/java");
    List<String> violations = new ArrayList<>();
    int overriding = 0;
    try (Stream<Path> files = Files.walk(sources)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
        boolean overrides = overridesEcjClass(sources.relativize(file));
        if (overrides) {
          overriding++;
        }
        violations.addAll(EclipseLicenseHeader.violations(file, overrides));
      }
    }
    // guards against a vacuous test, e.g. if the detection of overridden classes silently broke
    assertThat(overriding).as("number of files overriding an ECJ class").isPositive();
    assertThat(violations).isEmpty();
  }

  @Test
  void overriding_file_with_eclipse_header_passes() throws IOException {
    assertThat(EclipseLicenseHeader.violations(write(ECLIPSE_HEADER), true)).isEmpty();
  }

  @Test
  void overriding_file_without_header_fails() throws IOException {
    assertThat(EclipseLicenseHeader.violations(write(""), true)).hasSize(3);
  }

  @Test
  void overriding_file_with_only_our_header_fails() throws IOException {
    assertThat(EclipseLicenseHeader.violations(write(SONAR_HEADER), true)).hasSize(3);
  }

  @Test
  void overriding_file_with_eclipse_text_outside_the_header_fails() throws IOException {
    String body = "/* header */\n// Eclipse Public License SPDX-License-Identifier: EPL-2.0 Copyright (c)\n";
    assertThat(EclipseLicenseHeader.violations(write(body), true)).hasSize(3);
  }

  @Test
  void non_overriding_file_with_eclipse_header_fails() throws IOException {
    assertThat(EclipseLicenseHeader.violations(write(ECLIPSE_HEADER), false)).hasSize(1);
  }

  @Test
  void non_overriding_file_with_our_header_passes() throws IOException {
    assertThat(EclipseLicenseHeader.violations(write(SONAR_HEADER), false)).isEmpty();
  }

  @Test
  void non_overriding_file_without_header_passes() throws IOException {
    assertThat(EclipseLicenseHeader.violations(write("package a;\nclass A {}\n"), false)).isEmpty();
  }

  /**
   * A class is overridden when a copy of it exists in a jar of the classpath: our own classes are in target/classes,
   * so any copy coming from a jar is the one from org.eclipse.jdt.core.
   */
  private static boolean overridesEcjClass(Path javaFile) throws IOException {
    String resource = javaFile.toString().replace('\\', '/').replaceAll("\\.java$", ".class");
    List<URL> urls = Collections.list(EclipseLicenseHeaderTest.class.getClassLoader().getResources(resource));
    return urls.stream().anyMatch(url -> "jar".equals(url.getProtocol()));
  }

  private Path write(String content) throws IOException {
    return Files.writeString(Files.createTempFile(tmp, "Sample", ".java"), content + "package a;\nclass A {}\n");
  }
}
