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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Rules for the license header of the files in {@code src/main/java}:
 * <ul>
 *   <li>a file overriding an ECJ class is a derivative work and must keep the Eclipse header;</li>
 *   <li>a file written by us must not carry the Eclipse header, we do not license our own work to ECJ.</li>
 * </ul>
 */
final class EclipseLicenseHeader {

  private static final String EPL_NOTICE = "Eclipse Public License";
  private static final String EPL_SPDX = "SPDX-License-Identifier: EPL-2.0";
  private static final String COPYRIGHT = "Copyright (c)";

  private EclipseLicenseHeader() {
  }

  static List<String> violations(Path file, boolean overridesEcj) throws IOException {
    String header = leadingComment(Files.readString(file, StandardCharsets.UTF_8));
    List<String> violations = new ArrayList<>();
    if (overridesEcj) {
      requireInHeader(file, header, COPYRIGHT, violations);
      requireInHeader(file, header, EPL_NOTICE, violations);
      requireInHeader(file, header, EPL_SPDX, violations);
    } else if (header.contains(EPL_NOTICE) || header.contains(EPL_SPDX)) {
      violations.add(file + " does not override an ECJ class, it must not carry the Eclipse license header");
    }
    return violations;
  }

  private static void requireInHeader(Path file, String header, String expected, List<String> violations) {
    if (!header.contains(expected)) {
      violations.add(file + " overrides an ECJ class, its Eclipse license header must contain '" + expected + "'");
    }
  }

  /** The block comment at the very top of the file, or an empty string when there is none. */
  static String leadingComment(String source) {
    String trimmed = source.stripLeading();
    if (!trimmed.startsWith("/*")) {
      return "";
    }
    int end = trimmed.indexOf("*/");
    return end < 0 ? "" : trimmed.substring(0, end + 2);
  }
}
