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

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.IErrorHandlingPolicy;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.eclipse.jdt.internal.compiler.problem.ProblemHandler;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;
import org.eclipse.jdt.internal.compiler.problem.ProblemSeverities;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ProblemHandlingIT {

  @Test
  void ignores_unreportable_errors_without_aborting_compilation() {
    assertUnreportableErrorIsIgnored(DefaultErrorHandlingPolicies.proceedWithAllProblems());
  }

  @Test
  void ignores_unreportable_errors_under_the_ignore_all_policy() {
    assertUnreportableErrorIsIgnored(DefaultErrorHandlingPolicies.ignoreAllProblems());
  }

  @Test
  void still_records_errors_when_a_reference_context_is_available() {
    ProblemReporter reporter = newReporter();
    CompilationResult result = new CompilationResult("Example.java".toCharArray(), 0, 1, 100);
    CompilationUnitDeclaration context = new CompilationUnitDeclaration(reporter, result, 2);

    reporter.handle(IProblem.UndefinedType, new String[]{"Missing"}, 0, new String[]{"Missing"}, ProblemSeverities.Error, 0, 1, context, result);

    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).extracting(IProblem::getID).containsExactly(IProblem.UndefinedType);
  }

  @Test
  void closing_the_reporter_preserves_its_reference_context() {
    ProblemReporter reporter = newReporter();
    CompilationUnitDeclaration context = new CompilationUnitDeclaration(reporter, new CompilationResult("Example.java".toCharArray(), 0, 1, 100), 0);
    reporter.referenceContext = context;

    reporter.close();

    assertThat(reporter.referenceContext).isSameAs(context);
  }

  @Test
  void tolerates_a_method_wrongly_tagged_as_containing_missing_types() {
    assertThat(ProblemReporter.class.desiredAssertionStatus()).as("the missing-type regression requires assertions").isTrue();
    MethodBinding method = new MethodBinding(0, "method".toCharArray(), TypeBinding.VOID, Binding.NO_PARAMETERS, Binding.NO_EXCEPTIONS, null);
    assertThat(method.collectMissingTypes(null, true)).isNull();
    ProblemReporter reporter = newReporter();

    assertThatCode(() -> reporter.missingTypeInMethod(new MessageSend(), method)).doesNotThrowAnyException();
  }

  private static void assertUnreportableErrorIsIgnored(IErrorHandlingPolicy policy) {
    ProblemHandler handler = new ProblemHandler(policy, new CompilerOptions(), new DefaultProblemFactory());

    assertThatCode(() -> handler.handle(IProblem.UndefinedType, new String[]{"Missing"}, 0, new String[]{"Missing"},
      ProblemSeverities.Error, 0, 1, null, null)).doesNotThrowAnyException();
  }

  private static ProblemReporter newReporter() {
    return new ProblemReporter(DefaultErrorHandlingPolicies.proceedWithAllProblems(), new CompilerOptions(), new DefaultProblemFactory());
  }
}
