// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.actions;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;

import java.util.Set;

/**
 * A mutable action graph. Implementations of this interface must be thread-safe.
 */
public interface MutableActionGraph extends ActionGraph {

  /**
   * Attempts to register the action. If any of the action's outputs already has a generating
   * action, and the two actions are not compatible, then an {@link ActionConflictException} is
   * thrown. The internal data structure may be partially modified when that happens; it is not
   * guaranteed that all potential conflicts are detected, but at least one of them is.
   *
   * <p>For example, take three actions A, B, and C, where A creates outputs a and b, B creates just
   * b, and C creates c and b. There are two potential conflicts in this case, between A and B, and
   * between B and C. Depending on the ordering of calls to this method and the ordering of outputs
   * in the action output lists, either one or two conflicts are detected: if B is registered first,
   * then both conflicts are detected; if either A or C is registered first, then only one conflict
   * is detected.
   */
  void registerAction(ActionAnalysisMetadata action) throws ActionConflictException;

  /**
   * Removes an action from this action graph if it is present.
   *
   * <p>Throws {@link IllegalStateException} if one of the outputs of the action is in fact
   * generated by a different {@link Action} instance (even if they are sharable).
   */
  void unregisterAction(ActionAnalysisMetadata action);

  /**
   * Clear the action graph.
   */
  void clear();

  /**
   * This exception is thrown when a conflict between actions is detected. It contains information
   * about the artifact for which the conflict is found, and data about the two conflicting actions
   * and their owners.
   */
  public static final class ActionConflictException extends Exception {

    private final Artifact artifact;
    private final ActionAnalysisMetadata previousAction;
    private final ActionAnalysisMetadata attemptedAction;
    
    private static final int MAX_DIFF_ARTIFACTS_TO_REPORT = 5;

    public ActionConflictException(Artifact artifact, ActionAnalysisMetadata previousAction,
        ActionAnalysisMetadata attemptedAction) {
      super(
          String.format(
              "for %s, previous action: %s, attempted action: %s",
              artifact.prettyPrint(),
              previousAction.prettyPrint(),
              attemptedAction.prettyPrint()));
      this.artifact = artifact;
      this.previousAction = previousAction;
      this.attemptedAction = attemptedAction;
    }

    public Artifact getArtifact() {
      return artifact;
    }

    public void reportTo(EventHandler eventListener) {
      String msg =
          "file '"
              + artifact.prettyPrint()
              + "' is generated by these conflicting actions:\n"
              + suffix(attemptedAction, previousAction);
      eventListener.handle(Event.error(msg));
    }

    private static void addStringDetail(
        StringBuilder sb, String key, String valueA, String valueB) {
      valueA = valueA != null ? valueA : "(null)";
      valueB = valueB != null ? valueB : "(null)";

      sb.append(key).append(": ").append(valueA);
      if (!valueA.equals(valueB)) {
        sb.append(", ").append(valueB);
      }
      sb.append("\n");
    }

    private static void addListDetail(
        StringBuilder sb, String key, Iterable<Artifact> valueA, Iterable<Artifact> valueB) {
      Set<Artifact> setA = ImmutableSet.copyOf(valueA);
      Set<Artifact> setB = ImmutableSet.copyOf(valueB);
      SetView<Artifact> diffA = Sets.difference(setA, setB);
      SetView<Artifact> diffB = Sets.difference(setB, setA);

      sb.append(key).append(": ");
      if (diffA.isEmpty() && diffB.isEmpty()) {
        sb.append("are equal\n");
      } else {
        if (!diffA.isEmpty()) {
          sb.append(
              "Attempted action contains artifacts not in previous action (first "
                  + MAX_DIFF_ARTIFACTS_TO_REPORT
                  + "): \n");
          prettyPrintArtifactDiffs(sb, diffA);
        }

        if (!diffB.isEmpty()) {
          sb.append(
              "Previous action contains artifacts not in attempted action (first "
                  + MAX_DIFF_ARTIFACTS_TO_REPORT
                  + "): \n");
          prettyPrintArtifactDiffs(sb, diffB);
        }
      }
    }

    /** Pretty print action diffs (at most {@code MAX_DIFF_ARTIFACTS_TO_REPORT} lines). */
    private static void prettyPrintArtifactDiffs(StringBuilder sb, SetView<Artifact> diff) {
      for (Artifact artifact : Iterables.limit(diff, MAX_DIFF_ARTIFACTS_TO_REPORT)) {
        sb.append("\t" + artifact.prettyPrint() + "\n");
      }
    }

    // See also Actions.canBeShared()
    private String suffix(ActionAnalysisMetadata a, ActionAnalysisMetadata b) {
      // Note: the error message reveals to users the names of intermediate files that are not
      // documented in the UCBUILD language.  This error-reporting logic is rather elaborate but it
      // does help to diagnose some tricky situations.
      StringBuilder sb = new StringBuilder();
      ActionOwner aOwner = a.getOwner();
      ActionOwner bOwner = b.getOwner();
      boolean aNull = aOwner == null;
      boolean bNull = bOwner == null;

      addStringDetail(sb, "Label", aNull ? null : Label.print(aOwner.getLabel()),
          bNull ? null : Label.print(bOwner.getLabel()));
      addStringDetail(sb, "RuleClass", aNull ? null : aOwner.getTargetKind(),
          bNull ? null : bOwner.getTargetKind());
      addStringDetail(sb, "Configuration", aNull ? null : aOwner.getConfigurationChecksum(),
          bNull ? null : bOwner.getConfigurationChecksum());
      addStringDetail(sb, "Mnemonic", a.getMnemonic(), b.getMnemonic());

      if ((a instanceof ActionExecutionMetadata) && (b instanceof ActionExecutionMetadata)) {
        addStringDetail(
            sb,
            "Progress message",
            ((ActionExecutionMetadata) a).getProgressMessage(),
            ((ActionExecutionMetadata) b).getProgressMessage());
      }

      addStringDetail(
          sb,
          "PrimaryInput",
          a.getPrimaryInput() == null ? null : a.getPrimaryInput().toString(),
          b.getPrimaryInput() == null ? null : b.getPrimaryInput().toString());
      addStringDetail(
          sb, "PrimaryOutput", a.getPrimaryOutput().toString(), b.getPrimaryOutput().toString());

      // Only add list details if the primary input of A matches the input of B. Otherwise
      // the above information is enough and list diff detail is not needed.
      if ((a.getPrimaryInput() == null && b.getPrimaryInput() == null)
          || (a.getPrimaryInput() != null
              && b.getPrimaryInput() != null
              && a.getPrimaryInput().toString().equals(b.getPrimaryInput().toString()))) {
        addListDetail(sb, "MandatoryInputs", a.getMandatoryInputs(), b.getMandatoryInputs());
        addListDetail(sb, "Outputs", a.getOutputs(), b.getOutputs());
      }

      return sb.toString();
    }
  }
}
