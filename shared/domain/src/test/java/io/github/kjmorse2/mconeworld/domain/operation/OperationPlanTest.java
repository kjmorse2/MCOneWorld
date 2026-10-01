// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import static io.github.kjmorse2.mconeworld.fixtures.OperationFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.fixtures.OperationFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Structural and policy rules of plans and their value types. */
class OperationPlanTest {
  private static final OperationPlan FULL = OperationFixtures.fullPlan();

  /** A copy of FULL with some components replaced, through a mutable builder view. */
  private static OperationPlan with(UnaryOperator<Parts> change) {
    Parts p = change.apply(new Parts(FULL));
    return new OperationPlan(
        FULL.id(),
        FULL.type(),
        FULL.createdAt(),
        FULL.createdBy(),
        FULL.archive(),
        p.impact,
        p.targets,
        FULL.preconditions(),
        p.backup,
        p.steps,
        p.validations,
        p.rollback);
  }

  private static final class Parts {
    ImpactClass impact;
    List<OperationTarget> targets;
    BackupRequirement backup;
    List<StepDescriptor> steps;
    List<ValidationDescriptor> validations;
    RollbackDeclaration rollback;

    Parts(OperationPlan plan) {
      impact = plan.impact();
      targets = new ArrayList<>(plan.targets());
      backup = plan.backup();
      steps = new ArrayList<>(plan.steps());
      validations = new ArrayList<>(plan.validations());
      rollback = plan.rollback();
    }
  }

  private static void assertRejected(UnaryOperator<Parts> change) {
    assertThatThrownBy(() -> with(change))
        .isInstanceOfSatisfying(
            InvalidDomainValueException.class,
            e -> assertThat(e.code()).isEqualTo(DomainErrorCode.OPERATION_PLAN_INVALID));
  }

  @Test
  void fixturePlanIsValidAndImmutable() {
    assertThat(FULL.steps()).hasSize(3);
    assertThatThrownBy(() -> FULL.targets().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void stepsAndChecksAreRequiredAndBounded() {
    assertRejected(
        p -> {
          p.steps.clear();
          return p;
        });
    assertRejected(
        p -> {
          p.validations.clear();
          return p;
        });
    assertRejected(
        p -> {
          while (p.steps.size() <= OperationPlan.MAX_STEPS) {
            p.steps.add(p.steps.get(0));
          }
          return p;
        });
  }

  @Test
  void targetsAreDistinctAndStepIndexesExist() {
    assertRejected(
        p -> {
          p.targets.add(p.targets.get(0));
          return p;
        });
    assertRejected(
        p -> {
          p.steps.set(
              0,
              new StepDescriptor(
                  ActionKey.of("test:x"), text("x"), false, List.of(p.targets.size())));
          return p;
        });
  }

  @Test
  void onlySidecarMutatingPlansMaySkipBackupAndRollback() {
    BackupRequirement none = new BackupRequirement.NotRequired(text("sidecar transaction"));
    RollbackDeclaration notApplicable =
        new RollbackDeclaration(RollbackStrategy.NOT_APPLICABLE, text("atomic commit"));
    for (ImpactClass impact : ImpactClass.values()) {
      if (impact == ImpactClass.SIDECAR_MUTATING) {
        OperationPlan ok =
            with(
                p -> {
                  p.impact = impact;
                  p.backup = none;
                  p.rollback = notApplicable;
                  return p;
                });
        assertThat(ok.impact().requiresBackup()).isFalse();
      } else {
        assertThat(impact.requiresBackup()).isTrue();
        assertRejected(
            p -> {
              p.impact = impact;
              p.backup = none;
              p.rollback = new RollbackDeclaration(RollbackStrategy.MANUAL, text("m"));
              return p;
            });
        assertRejected(
            p -> {
              p.impact = impact;
              p.rollback = notApplicable;
              return p;
            });
      }
    }
  }

  @Test
  void restoreBackupNeedsABackup() {
    assertRejected(
        p -> {
          p.impact = ImpactClass.SIDECAR_MUTATING;
          p.backup = new BackupRequirement.NotRequired(text("none"));
          return p;
        });
  }

  @Test
  void stepTargetIndexesMustBeStrictlyIncreasing() {
    assertThatThrownBy(
            () -> new StepDescriptor(ActionKey.of("a:b"), text("x"), false, List.of(1, 1)))
        .isInstanceOf(InvalidDomainValueException.class);
    assertThatThrownBy(() -> new StepDescriptor(ActionKey.of("a:b"), text("x"), false, List.of(-1)))
        .isInstanceOf(InvalidDomainValueException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "noNamespace",
        "Upper:case",
        "mconeworld:",
        ":path",
        "a:b/../c",
        "a:./b",
        "a:b//c",
        "a:b/",
        "a:b c",
        "a:b\\c"
      })
  void malformedNamespacedKeysAreRejected(String text) {
    assertThatThrownBy(() -> OperationType.of(text))
        .isInstanceOfSatisfying(
            InvalidDomainValueException.class,
            e -> assertThat(e.code()).isEqualTo(DomainErrorCode.KEY_MALFORMED));
    assertThatThrownBy(() -> ActionKey.of(text)).isInstanceOf(InvalidDomainValueException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"/home/kj/world", "C:\\world", "world:/abs", "world:../up", "world:a/../../b"})
  void inputKeysCannotHoldHostPathsOrTraversal(String text) {
    assertThatThrownBy(() -> InputKey.of(text)).isInstanceOf(InvalidDomainValueException.class);
  }

  @Test
  void wellFormedKeys() {
    assertThat(InputKey.of("world:region/r.-1.0.mca").value()).isEqualTo("world:region/r.-1.0.mca");
    assertThat(OperationType.of("mconeworld:season/reset").toString())
        .isEqualTo("mconeworld:season/reset");
    assertThat(ActionKey.of("x".repeat(60) + ":" + "y".repeat(67)).value()).hasSize(128);
    assertThatThrownBy(() -> ActionKey.of("x".repeat(60) + ":" + "y".repeat(68)))
        .isInstanceOf(InvalidDomainValueException.class);
  }

  @Test
  void extensionTargetKeysCannotHoldPaths() {
    ActionKey kind = ActionKey.of("example:warp");
    assertThat(new OperationTarget.Extension(kind, "spawn-hub").kind()).isEqualTo("extension");
    for (String bad : List.of("a/b", "a\\b", "", "x".repeat(129), "sp ace")) {
      assertThatThrownBy(() -> new OperationTarget.Extension(kind, bad))
          .isInstanceOf(InvalidDomainValueException.class);
    }
  }

  @Test
  void operationTextIsBoundedSingleLine() {
    assertThat(text("x".repeat(OperationText.MAX_CODE_POINTS)).value()).hasSize(1024);
    for (String bad :
        List.of("", " padded", "two\nlines", "x".repeat(1025), "tab\there", "\u202Espoof")) {
      assertThatThrownBy(() -> OperationText.of(bad))
          .isInstanceOfSatisfying(
              InvalidDomainValueException.class,
              e -> assertThat(e.code()).isEqualTo(DomainErrorCode.TEXT_INVALID));
    }
  }

  @Test
  void toolIdentityIsBoundedAndPathFree() {
    assertThat(ToolIdentity.of("mconeworld-paper", "0.1.0+build.5").toString())
        .isEqualTo("mconeworld-paper 0.1.0+build.5");
    assertThatThrownBy(() -> ToolIdentity.of("/usr/bin/tool", "1"))
        .isInstanceOf(InvalidDomainValueException.class);
    assertThatThrownBy(() -> ToolIdentity.of("tool", "1 beta"))
        .isInstanceOf(InvalidDomainValueException.class);
  }

  @Test
  void factValuesAreTokensAndSnapshotsAreBounded() {
    var key = InputKey.of("minecraft:data_version");
    var facts = new java.util.TreeMap<InputKey, String>();
    facts.put(key, "not a token");
    assertThatThrownBy(
            () ->
                new PreconditionSnapshot(
                    new ExpectedManifest.Absent(), new java.util.TreeMap<>(), facts))
        .isInstanceOf(InvalidDomainValueException.class);
    var many = new java.util.TreeMap<InputKey, String>();
    for (int i = 0; i <= PreconditionSnapshot.MAX_ENTRIES; i++) {
      many.put(InputKey.of("world:f" + i), "1");
    }
    assertThatThrownBy(
            () ->
                new PreconditionSnapshot(
                    new ExpectedManifest.Absent(), new java.util.TreeMap<>(), many))
        .isInstanceOfSatisfying(
            InvalidDomainValueException.class,
            e -> assertThat(e.code()).isEqualTo(DomainErrorCode.VALUE_INVALID));
    assertThatThrownBy(() -> new ExpectedManifest.AtRevision(-1, OperationFixtures.checksum('a')))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void backupRequirementsAcceptOnlyMatchingConfirmations() {
    BackupReference other =
        new BackupReference(OperationFixtures.BACKUP.id(), OperationFixtures.checksum('c'));
    assertThat(new BackupRequirement.NewBackup().acceptsConfirmation(other)).isTrue();
    assertThat(new BackupRequirement.NewBackup().acceptsConfirmation(null)).isFalse();
    var existing = new BackupRequirement.ExistingBackup(OperationFixtures.BACKUP);
    assertThat(existing.acceptsConfirmation(OperationFixtures.BACKUP)).isTrue();
    assertThat(existing.acceptsConfirmation(other)).isFalse();
    var none = new BackupRequirement.NotRequired(text("sidecar only"));
    assertThat(none.acceptsConfirmation(null)).isTrue();
    assertThat(none.acceptsConfirmation(other)).isFalse();
  }
}
