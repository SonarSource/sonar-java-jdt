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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FileASTRequestor;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ASTParserRecoveryIT {

  @TempDir
  Path temp;

  @Test
  void returns_an_ast_without_a_project_or_system_library() {
    ASTParser parser = newParser(false);
    parser.setUnitName("Example.java");
    parser.setSource("class Example { Missing value; }".toCharArray());

    CompilationUnit unit = (CompilationUnit) parser.createAST(null);

    assertThat(unit.types()).hasSize(1);
    assertThat(errors(unit)).extracting(IProblem::getID).contains(IProblem.UndefinedType);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void delivers_every_ast_and_preserves_bindings_despite_unresolved_types(int brokenFileIndex) throws IOException {
    Path before = Files.writeString(temp.resolve("Before.java"), "class Before { String known; }");
    Path broken = Files.writeString(temp.resolve("Broken.java"), "class Broken { Missing unknown; String known; }");
    Path after = Files.writeString(temp.resolve("After.java"), "class After { String known; }");
    List<Path> inputs = new ArrayList<>(List.of(before, after));
    inputs.add(brokenFileIndex, broken);
    List<ParsedSource> received = new ArrayList<>();

    newParser(true).createASTs(
      inputs.stream().map(Path::toString).toArray(String[]::new),
      new String[]{"UTF-8", "UTF-8", "UTF-8"},
      new String[0],
      new FileASTRequestor() {
        @Override
        public void acceptAST(String sourceFilePath, CompilationUnit ast) {
          received.add(new ParsedSource(Path.of(sourceFilePath), ast));
        }
      },
      null);

    assertThat(received).extracting(ParsedSource::path).containsExactlyInAnyOrderElementsOf(inputs);
    for (ParsedSource source : received) {
      assertThat(source.unit().types()).as("types in %s", source.path()).hasSize(1);
      TypeDeclaration type = (TypeDeclaration) source.unit().types().getFirst();
      assertThat(type.resolveBinding()).as("type binding in %s", source.path()).isNotNull();
      assertThat(type.resolveBinding().isRecovered()).isFalse();
      int knownFieldIndex = 0;
      if (source.path().equals(broken)) {
        assertThat(errors(source.unit())).extracting(IProblem::getID).containsExactly(IProblem.UndefinedType);
        ITypeBinding missing = type.getFields()[0].getType().resolveBinding();
        assertThat(missing).as("recovered binding of Missing").isNotNull();
        assertThat(missing.isRecovered()).isTrue();
        knownFieldIndex = 1;
      } else {
        assertThat(errors(source.unit())).as("errors in %s", source.path()).isEmpty();
      }
      ITypeBinding known = type.getFields()[knownFieldIndex].getType().resolveBinding();
      assertThat(known).as("String binding in %s", source.path()).isNotNull();
      assertThat(known.isRecovered()).isFalse();
      assertThat(known.getQualifiedName()).isEqualTo("java.lang.String");
    }
  }

  private static ASTParser newParser(boolean includeRunningVm) {
    ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
    var options = new HashMap<>(JavaCore.getOptions());
    JavaCore.setComplianceOptions("17", options);
    parser.setCompilerOptions(options);
    parser.setEnvironment(new String[0], new String[0], new String[0], includeRunningVm);
    parser.setResolveBindings(true);
    parser.setBindingsRecovery(true);
    return parser;
  }

  private static List<IProblem> errors(CompilationUnit unit) {
    return Arrays.stream(unit.getProblems()).filter(IProblem::isError).toList();
  }

  private record ParsedSource(Path path, CompilationUnit unit) {
  }
}
