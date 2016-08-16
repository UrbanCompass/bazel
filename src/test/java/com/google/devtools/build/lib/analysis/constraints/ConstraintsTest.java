// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.constraints;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.testutil.UnknownRuleConfiguredTarget;
import com.google.devtools.build.lib.util.FileTypeSet;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Set;

/**
 * Tests for the constraint enforcement system.
 */
@RunWith(JUnit4.class)
public class ConstraintsTest extends AbstractConstraintsTest {

  @Before
  public final void createBuildFile() throws Exception {
    // Support files for RuleClassWithImplicitAndLateBoundDefaults:
    scratch.file("helpers/UCBUILD",
        "sh_library(name = 'implicit', srcs = ['implicit.sh'])",
        "sh_library(name = 'latebound', srcs = ['latebound.sh'])",
        "sh_library(name = 'default', srcs = ['default.sh'])");
    scratch.file("config/UCBUILD",
        "config_setting(name = 'a', values = {'define': 'mode=a'})",
        "config_setting(name = 'b', values = {'define': 'mode=b'})");
  }

  /**
   * Dummy rule class for testing rule class defaults. This class applies valid defaults. Note
   * that the specified environments must be independently created.
   */
  private static final class RuleClassDefaultRule implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .setUndocumented()
          .compatibleWith(env.getLabel("//buildenv/rule_class_compat:b"))
          .restrictedTo(env.getLabel("//buildenv/rule_class_restrict:d"))
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("rule_class_default")
          .ancestors(BaseRuleClasses.RuleBase.class)
          .factoryClass(UnknownRuleConfiguredTarget.class)
          .build();
    }
  }

  /**
   * Dummy rule class for testing rule class defaults. This class applies invalid defaults. Note
   * that the specified environments must be independently created.
   */
  private static final class BadRuleClassDefaultRule implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .setUndocumented()
          // These defaults are invalid since compatibleWith and restrictedTo can't mix
          // environments from the same group.
          .compatibleWith(env.getLabel("//buildenv/rule_class_compat:a"))
          .restrictedTo(env.getLabel("//buildenv/rule_class_compat:b"))
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("bad_rule_class_default")
          .ancestors(BaseRuleClasses.RuleBase.class)
          .factoryClass(UnknownRuleConfiguredTarget.class)
          .build();
    }
  }

  private static final class RuleClassWithImplicitAndLateBoundDefaults implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .setUndocumented()
          .add(Attribute.attr("$implicit", BuildType.LABEL)
              .value(Label.parseAbsoluteUnchecked("//helpers:implicit")))
          .add(Attribute.attr(":latebound", BuildType.LABEL)
              .value(
                  new Attribute.LateBoundLabel<BuildConfiguration>() {
                    @Override
                    public Label resolve(Rule rule, AttributeMap attributes,
                        BuildConfiguration configuration) {
                      return Label.parseAbsoluteUnchecked("//helpers:latebound");
                    }
                  }))
          .add(Attribute.attr("normal", BuildType.LABEL)
              .allowedFileTypes(FileTypeSet.NO_FILE)
              .value(Label.parseAbsoluteUnchecked("//helpers:default")))
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("rule_with_implicit_and_latebound_deps")
          .ancestors(BaseRuleClasses.RuleBase.class)
          .factoryClass(UnknownRuleConfiguredTarget.class)
          .build();
    }
  }

  private static final class RuleClassWithEnforcedImplicitAttribute implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .setUndocumented()
          .add(Attribute.attr("$implicit", BuildType.LABEL)
              .value(Label.parseAbsoluteUnchecked("//helpers:implicit"))
              .checkConstraints())
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("rule_with_enforced_implicit_deps")
          .ancestors(BaseRuleClasses.RuleBase.class)
          .factoryClass(UnknownRuleConfiguredTarget.class)
          .build();
    }
  }

  private static final class ConstraintExemptRuleClass implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .setUndocumented()
          .exemptFromConstraintChecking("for testing removal of restricted_to / compatible_with")
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("totally_free_rule")
          .ancestors(BaseRuleClasses.RuleBase.class)
          .factoryClass(UnknownRuleConfiguredTarget.class)
          .build();
    }
  }

  /**
   * Injects the rule class default rules into the default test rule class provider.
   */
  @Override
  protected ConfiguredRuleClassProvider getRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder.addRuleDefinition(new RuleClassDefaultRule());
    builder.addRuleDefinition(new BadRuleClassDefaultRule());
    builder.addRuleDefinition(new RuleClassWithImplicitAndLateBoundDefaults());
    builder.addRuleDefinition(new RuleClassWithEnforcedImplicitAttribute());
    builder.addRuleDefinition(new ConstraintExemptRuleClass());
    return builder.build();
  }

  /**
   * Writes the environments and environment groups referred to by the rule class defaults.
   */
  private void writeRuleClassDefaultEnvironments() throws Exception {
    new EnvironmentGroupMaker("buildenv/rule_class_compat").setEnvironments("a", "b")
        .setDefaults("a").make();
    new EnvironmentGroupMaker("buildenv/rule_class_restrict").setEnvironments("c", "d")
        .setDefaults("c").make();
  }

  /**
   * By default, a rule *implicitly* supports all defaults, meaning the explicitly known
   * environment set is empty.
   */
  @Test
  public void defaultSupportedEnvironments() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make();
    String ruleDef = getDependencyRule();
    assertThat(supportedEnvironments("dep", ruleDef)).isEmpty();
  }

  /**
   * "Constraining" a rule's environments explicitly sets them.
   */
  @Test
  public void constrainedSupportedEnvironments() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
        .make();
    String ruleDef = getDependencyRule(constrainedTo("//buildenv/foo:c"));
    assertThat(supportedEnvironments("dep", ruleDef))
        .containsExactlyElementsIn(asLabelSet("//buildenv/foo:c"));
  }

  /**
   * Specifying compatibility adds the specified environments to the defaults.
   */
  @Test
  public void compatibleSupportedEnvironments() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
        .make();
    String ruleDef = getDependencyRule(compatibleWith("//buildenv/foo:c"));
    assertThat(supportedEnvironments("dep", ruleDef))
        .containsExactlyElementsIn(asLabelSet("//buildenv/foo:a", "//buildenv/foo:c"));
  }

  /**
   * A rule can't support *no* environments.
   */
  @Test
  public void supportedEnvironmentsConstrainedtoNothing() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make();
    reporter.removeHandler(failFastHandler);
    String ruleDef = getDependencyRule(constrainedTo());
    assertNull(scratchConfiguredTarget("hello", "dep", ruleDef));
    assertContainsEvent("attribute cannot be empty");
  }

  /**
   * Restrict the environments within one group, declare compatibility for another.
   */
  @Test
  public void supportedEnvironmentsInMultipleGroups() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make();
    new EnvironmentGroupMaker("buildenv/bar").setEnvironments("c", "d").setDefaults("c").make();
    String ruleDef = getDependencyRule(
        constrainedTo("//buildenv/foo:b"), compatibleWith("//buildenv/bar:d"));
    assertThat(supportedEnvironments("dep", ruleDef))
        .containsExactlyElementsIn(
            asLabelSet("//buildenv/foo:b", "//buildenv/bar:c", "//buildenv/bar:d"));
  }

  /**
   * The same label can't appear in both a constraint and a compatibility declaration.
   */
  @Test
  public void sameEnvironmentCompatibleAndRestricted() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make();
    reporter.removeHandler(failFastHandler);
    String ruleDef = getDependencyRule(
        constrainedTo("//buildenv/foo:b"), compatibleWith("//buildenv/foo:b"));
    assertNull(scratchConfiguredTarget("hello", "dep", ruleDef));
    assertContainsEvent("//buildenv/foo:b cannot appear both here and in restricted_to");
  }

  /**
   * Two labels from the same group can't appear in different attributes.
   */
  @Test
  public void sameGroupCompatibleAndRestricted() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make();
    reporter.removeHandler(failFastHandler);
    String ruleDef = getDependencyRule(
        constrainedTo("//buildenv/foo:a"), compatibleWith("//buildenv/foo:b"));
    assertNull(scratchConfiguredTarget("hello", "dep", ruleDef));
    assertContainsEvent(
        "//buildenv/foo:b and //buildenv/foo:a belong to the same environment group");
  }

  /**
   * Tests that rule class defaults change a rule's default set of environments.
   */
  @Test
  public void supportedEnvironmentsRuleClassDefaults() throws Exception {
    writeRuleClassDefaultEnvironments();
    String ruleDef = "rule_class_default(name = 'a')";
    Set<Label> expectedEnvironments = asLabelSet("//buildenv/rule_class_compat:a",
        "//buildenv/rule_class_compat:b", "//buildenv/rule_class_restrict:d");
    assertThat(supportedEnvironments("a", ruleDef)).containsExactlyElementsIn(expectedEnvironments);
  }

  /**
   * Tests that explicit declarations override rule class defaults.
   */
  @Test
  public void explicitAttributesOverrideRuleClassDefaults() throws Exception {
    writeRuleClassDefaultEnvironments();
    String ruleDef = "rule_class_default("
        + "    name = 'a',"
        + "    compatible_with = ['//buildenv/rule_class_restrict:c'],"
        + "    restricted_to = ['//buildenv/rule_class_compat:a'],"
        + ")";
    Set<Label> expectedEnvironments = asLabelSet("//buildenv/rule_class_compat:a",
        "//buildenv/rule_class_restrict:c", "//buildenv/rule_class_restrict:d");
    assertThat(supportedEnvironments("a", ruleDef)).containsExactlyElementsIn(expectedEnvironments);
  }

  /**
   * Tests that a rule's "known" supported environments includes those from groups referenced
   * in rule class defaults but not in explicit rule attributes.
   */
  @Test
  public void knownEnvironmentsIncludesThoseFromRuleClassDefaults() throws Exception {
    writeRuleClassDefaultEnvironments();
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make();
    String ruleDef = "rule_class_default("
        + "    name = 'a',"
        + "    restricted_to = ['//buildenv/foo:b'],"
        + ")";
    Set<Label> expectedEnvironments = asLabelSet("//buildenv/rule_class_compat:a",
        "//buildenv/rule_class_compat:b", "//buildenv/rule_class_restrict:d",
        "//buildenv/foo:b");
    assertThat(supportedEnvironments("a", ruleDef)).containsExactlyElementsIn(expectedEnvironments);
  }

  /**
   * Tests that environments from the same group can't appear in both restriction and
   * compatibility rule class defaults.
   */
  @Test
  public void sameEnvironmentRuleClassCompatibleAndRestricted() throws Exception {
    writeRuleClassDefaultEnvironments();
    reporter.removeHandler(failFastHandler);
    String ruleDef = "bad_rule_class_default(name = 'a')";
    assertNull(scratchConfiguredTarget("hello", "a", ruleDef));
    assertContainsEvent("//buildenv/rule_class_compat:a and //buildenv/rule_class_compat:b "
        + "belong to the same environment group");
  }

  /**
   * Tests that a dependency is valid if both rules implicitly inherit all default environments.
   */
  @Test
  public void allDefaults() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(),
        getDependingRule());
    assertNotNull(getConfiguredTarget("//hello:main"));
    assertNoEvents();
  }

  /**
   * Tests that a dependency is valid when both rules explicitly declare the same constraints.
   */
  @Test
  public void sameConstraintsDeclaredExplicitly() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(constrainedTo("//buildenv/foo:b")),
        getDependingRule(constrainedTo("//buildenv/foo:b")));
    assertNotNull(getConfiguredTarget("//hello:main"));
    assertNoEvents();
  }

  /**
   * Tests that a dependency is valid when both the depender and dependency explicitly declare
   * their constraints and the depender supports a subset of the dependency's environments
   */
  @Test
  public void validConstraintsDeclaredExplicitly() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(constrainedTo("//buildenv/foo:a", "//buildenv/foo:b")),
        getDependingRule(constrainedTo("//buildenv/foo:b")));
    assertNotNull(getConfiguredTarget("//hello:main"));
    assertNoEvents();
  }

  /**
   * Tests that a dependency is invalid when both the depender and dependency explicitly declare
   * their constraints and the depender supports an environment the dependency doesn't.
   */
  @Test
  public void invalidConstraintsDeclaredExplicitly() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(constrainedTo("//buildenv/foo:b")),
        getDependingRule(constrainedTo("//buildenv/foo:a", "//buildenv/foo:b")));
    reporter.removeHandler(failFastHandler);
    assertNull(getConfiguredTarget("//hello:main"));
    assertContainsEvent(
        "dependency //hello:dep doesn't support expected environment: //buildenv/foo:a");
  }

  /**
   * Tests that a dependency is valid when both rules add the same set of environments to their
   * defaults.
   */
  @Test
  public void sameCompatibilityConstraints() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
        .make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(compatibleWith("//buildenv/foo:b", "//buildenv/foo:c")),
        getDependingRule(compatibleWith("//buildenv/foo:b", "//buildenv/foo:c")));
    assertNotNull(getConfiguredTarget("//hello:main"));
    assertNoEvents();
  }

  /**
   * Tests that a dependency is valid when both rules add environments to their defaults and
   * the depender only adds environments also added by the dependency.
   */
  @Test
  public void validCompatibilityConstraints() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
        .make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(compatibleWith("//buildenv/foo:b", "//buildenv/foo:c")),
        getDependingRule(compatibleWith("//buildenv/foo:c")));
    assertNotNull(getConfiguredTarget("//hello:main"));
    assertNoEvents();
  }

  /**
   * Tests that a dependency is invalid when both rules add environments to their defaults and
   * the depender adds environments not added by the dependency.
   */
  @Test
  public void invalidCompatibilityConstraints() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
        .make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(compatibleWith("//buildenv/foo:c")),
        getDependingRule(compatibleWith("//buildenv/foo:b", "//buildenv/foo:c")));
    reporter.removeHandler(failFastHandler);
    assertNull(getConfiguredTarget("//hello:main"));
    assertContainsEvent(
        "dependency //hello:dep doesn't support expected environment: //buildenv/foo:b");
  }

  /**
   * Tests the error message when the dependency is missing multiple expected environments.
   */
  @Test
  public void multipleMissingEnvironments() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
        .make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(),
        getDependingRule(compatibleWith("//buildenv/foo:b", "//buildenv/foo:c")));
    reporter.removeHandler(failFastHandler);
    assertNull(getConfiguredTarget("//hello:main"));
    assertContainsEvent("dependency //hello:dep doesn't support expected environments: "
        + "//buildenv/foo:b, //buildenv/foo:c");
  }

  /**
   * Tests a valid dependency including environments from different groups.
   */
  @Test
  public void validMultigroupConstraints() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
        .make();
    new EnvironmentGroupMaker("buildenv/bar").setEnvironments("d", "e", "f").setDefaults("d")
        .make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(constrainedTo("//buildenv/foo:b", "//buildenv/foo:c"),
            compatibleWith("//buildenv/bar:e")),
        getDependingRule(constrainedTo("//buildenv/foo:c"), compatibleWith("//buildenv/bar:e")));
    assertNotNull(getConfiguredTarget("//hello:main"));
    assertNoEvents();
  }

  /**
   * Tests an invalid dependency including environments from different groups.
   */
  @Test
  public void invalidMultigroupConstraints() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
        .make();
    new EnvironmentGroupMaker("buildenv/bar").setEnvironments("d", "e", "f").setDefaults("d")
        .make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(constrainedTo("//buildenv/foo:c"), compatibleWith("//buildenv/bar:e")),
        getDependingRule(constrainedTo("//buildenv/foo:b", "//buildenv/foo:c"),
            compatibleWith("//buildenv/bar:e")));
    reporter.removeHandler(failFastHandler);
    assertNull(getConfiguredTarget("//hello:main"));
    assertContainsEvent(
        "dependency //hello:dep doesn't support expected environment: //buildenv/foo:b");
  }

  /**
   * Tests a valid dependency where the dependency doesn't "know" about the expected environment's
   * group, but implicitly supports it because that environment is a default.
   */
  @Test
  public void validConstraintsUnknownEnvironmentToDependency() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a", "b")
        .make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(),
        getDependingRule(constrainedTo("//buildenv/foo:b")));
    assertNotNull(getConfiguredTarget("//hello:main"));
    assertNoEvents();
  }

  /**
   * Tests an invalid dependency where the dependency doesn't "know" about the expected
   * environment's group and doesn't support it because it isn't a default.
   */
  @Test
  public void invalidConstraintsUnknownEnvironmentToDependency() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a", "b")
        .make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(),
        getDependingRule(constrainedTo("//buildenv/foo:c")));
    reporter.removeHandler(failFastHandler);
    assertNull(getConfiguredTarget("//hello:main"));
    assertContainsEvent(
        "dependency //hello:dep doesn't support expected environment: //buildenv/foo:c");
  }

  /**
   * Tests a valid dependency where the depender doesn't "know" about one of the dependency's
   * groups, the depender implicitly supports that group's defaults, and all of those defaults
   * are accounted for in the dependency.
   */
  @Test
  public void validConstraintsUnknownEnvironmentToDependender() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
        .make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(constrainedTo("//buildenv/foo:a", "//buildenv/foo:b")),
        getDependingRule());
    assertNotNull(getConfiguredTarget("//hello:main"));
    assertNoEvents();
  }

  /**
   * Tests an invalid dependency where the depender doesn't "know" about one of the dependency's
   * groups, the depender implicitly supports that group's defaults, and one of those defaults
   * isn't accounted for in the dependency.
   */
  @Test
  public void invalidConstraintsUnknownEnvironmentToDependender() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
        .make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(constrainedTo("//buildenv/foo:b")),
        getDependingRule());
    reporter.removeHandler(failFastHandler);
    assertNull(getConfiguredTarget("//hello:main"));
    assertContainsEvent(
        "dependency //hello:dep doesn't support expected environment: //buildenv/foo:a");
  }

  /**
   * Tests the case where one dependency is valid and another one isn't.
   */
  @Test
  public void oneDependencyIsInvalid() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make();
    scratch.file("hello/UCBUILD",
        getRuleDef("sh_library", "bad_dep", constrainedTo("//buildenv/foo:b")),
        getRuleDef("sh_library", "good_dep", compatibleWith("//buildenv/foo:b")),
        getRuleDef("sh_library", "depender",
            constrainedTo("//buildenv/foo:a", "//buildenv/foo:b"),
            getAttrDef("deps", "good_dep", "bad_dep")));
    reporter.removeHandler(failFastHandler);
    assertNull(getConfiguredTarget("//hello:depender"));
    assertContainsEvent("//hello:bad_dep doesn't support expected environment: //buildenv/foo:a");
    assertDoesNotContainEvent("//hello:good_dep");
  }

  @Test
  public void constraintEnforcementDisabled() throws Exception {
    useConfiguration("--enforce_constraints=0");
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
        .make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(),
        getDependingRule(compatibleWith("//buildenv/foo:b", "//buildenv/foo:c")));
    assertNotNull(getConfiguredTarget("//hello:main"));
    assertNoEvents();
  }

  @Test
  public void constraintEnforcementDisabledHostConfig() throws Exception {
    useConfiguration("--enforce_constraints=0");
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults().make();
    scratch.file("hello/UCBUILD",
        "genrule(",
        "    name = 'gen',",
        "    srcs = [],",
        "    outs = ['gen.out'],",
        "    cmd = '',",
        "    tools = [':main'])",
        getDependencyRule(),
        getDependingRule(compatibleWith("//buildenv/foo:a")));
    assertNotNull(getConfiguredTarget("//hello:gen"));
    assertNoEvents();
  }

  /**
   * Tests that package defaults compatibility produces a valid dependency that would otherwise
   * be invalid.
   */
  @Test
  public void compatibilityPackageDefaults() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make();
    scratch.file("hello/UCBUILD",
        "package(default_compatible_with = ['//buildenv/foo:b'])",
        getDependencyRule(),
        getDependingRule(compatibleWith("//buildenv/foo:b")));
    assertNotNull(getConfiguredTarget("//hello:main"));
    assertNoEvents();
  }

  /**
   * Tests that a rule's compatibility declaration overrides its package defaults compatibility.
   */
  @Test
  public void packageDefaultsCompatibilityOverride() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make();
    // We intentionally create an invalid dependency structure vs. a valid one. If we tested on
    // a valid one, this test wouldn't be able to distinguish between rule declarations overriding
    // package defaults and package defaults overriding rule declarations.
    scratch.file("hello/UCBUILD",
        "package(default_compatible_with = ['//buildenv/foo:b'])",
        getDependencyRule(compatibleWith("//buildenv/foo:a")),
        getDependingRule(compatibleWith("//buildenv/foo:a", "//buildenv/foo:b")));
    reporter.removeHandler(failFastHandler);
    assertNull(getConfiguredTarget("//hello:main"));
    assertContainsEvent(
        "dependency //hello:dep doesn't support expected environment: //buildenv/foo:b");
  }

  /**
   * Tests that package defaults restriction produces an valid dependency that would otherwise
   * be invalid.
   */
  @Test
  public void restrictionPackageDefaults() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a", "b")
        .make();
    scratch.file("hello/UCBUILD",
        "package(default_restricted_to = ['//buildenv/foo:b'])",
        getDependencyRule(constrainedTo("//buildenv/foo:b")),
        getDependingRule());
    assertNotNull(getConfiguredTarget("//hello:main"));
    assertNoEvents();
  }

  /**
   * Tests that a rule's restriction declaration overrides its package defaults restriction.
   */
  @Test
  public void packageDefaultsRestrictionOverride() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make();
    // We intentionally create an invalid dependency structure vs. a valid one. If we tested on
    // a valid one, this test wouldn't be able to distinguish between rule declarations overriding
    // package defaults and package defaults overriding rule declarations.
    scratch.file("hello/UCBUILD",
        "package(default_restricted_to = ['//buildenv/foo:b'])",
        getDependencyRule(constrainedTo("//buildenv/foo:a")),
        getDependingRule(constrainedTo("//buildenv/foo:a", "//buildenv/foo:b")));
    reporter.removeHandler(failFastHandler);
    assertNull(getConfiguredTarget("//hello:main"));
    assertContainsEvent(
        "dependency //hello:dep doesn't support expected environment: //buildenv/foo:b");
  }

  /**
   * Tests that "default_compatible_with" fills in a rule's "compatible_with" when not specified
   * by the rule. This is different than, e.g., the rule declaration / rule class defaults model,
   * where the "compatible_with" / "restricted_to" values of rule class defaults are merged together
   * before being supplied to the rule. See comments in DependencyResolver for more discussion.
   */
  @Test
  public void packageDefaultsDirectlyFillRuleAttributes() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make();
    scratch.file("hello/UCBUILD",
        "package(default_restricted_to = ['//buildenv/foo:b'])",
        getDependencyRule(compatibleWith("//buildenv/foo:a")));
    reporter.removeHandler(failFastHandler);
    assertNull(getConfiguredTarget("//hello:dep"));
    assertContainsEvent("//buildenv/foo:a and //buildenv/foo:b belong to the same "
        + "environment group. They should be declared together either here or in restricted_to");
  }

  @Test
  public void hostDependenciesAreNotChecked() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make();
    scratch.file("hello/UCBUILD",
        "sh_binary(name = 'host_tool',",
        "    srcs = ['host_tool.sh'],",
        "    restricted_to = ['//buildenv/foo:b'])",
        "genrule(",
        "    name = 'hello',",
        "    srcs = [],",
        "    outs = ['hello.out'],",
        "    cmd = '',",
        "    tools = [':host_tool'],",
        "    compatible_with = ['//buildenv/foo:a'])");
    assertNotNull(getConfiguredTarget("//hello:hello"));
    assertNoEvents();
  }

  @Test
  public void hostDependenciesNotCheckedNoDistinctHostConfiguration() throws Exception {
    useConfiguration("--nodistinct_host_configuration");
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make();
    scratch.file("hello/UCBUILD",
        "sh_binary(name = 'host_tool',",
        "    srcs = ['host_tool.sh'],",
        "    restricted_to = ['//buildenv/foo:b'])",
        "genrule(",
        "    name = 'hello',",
        "    srcs = [],",
        "    outs = ['hello.out'],",
        "    cmd = '',",
        "    tools = [':host_tool'],",
        "    compatible_with = ['//buildenv/foo:a'])");
    assertNotNull(getConfiguredTarget("//hello:hello"));
    assertNoEvents();
  }

  @Test
  public void implicitAndLateBoundDependenciesAreNotChecked() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make();
    scratch.file("hello/UCBUILD",
        "rule_with_implicit_and_latebound_deps(",
        "    name = 'hi',",
        "    compatible_with = ['//buildenv/foo:b'])");
    assertNotNull(getConfiguredTarget("//hello:hi"));
    // Note that the event "cannot build rule_with_implicit_and_latebound_deps" *does* occur
    // because of the implementation of UnknownRuleConfiguredTarget.
    assertDoesNotContainEvent(":implicit doesn't support expected environment");
    assertDoesNotContainEvent(":latebound doesn't support expected environment");
    assertDoesNotContainEvent("normal doesn't support expected environment");
  }

  @Test
  public void implicitDepsWithWhiteListedAttributeAreChecked() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make();
    scratch.file("hello/UCBUILD",
        "rule_with_enforced_implicit_deps(",
        "    name = 'hi',",
        "    compatible_with = ['//buildenv/foo:b'])");
    reporter.removeHandler(failFastHandler);
    assertNull(getConfiguredTarget("//hello:hi"));
    assertContainsEvent(
        "dependency //helpers:implicit doesn't support expected environment: //buildenv/foo:b");
  }

  @Test
  public void outputFilesAreChecked() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make();
    scratch.file("hello/UCBUILD",
        "genrule(name = 'gen', srcs = [], outs = ['shlib.sh'], cmd = '')",
        "sh_library(",
        "    name = 'shlib',",
        "    srcs = ['shlib.sh'],",
        "    data = ['whatever.txt'],",
        "    compatible_with = ['//buildenv/foo:a'])");
    reporter.removeHandler(failFastHandler);
    assertNull(getConfiguredTarget("//hello:shlib"));
    assertContainsEvent(
        "dependency //hello:gen doesn't support expected environment: //buildenv/foo:a");
  }

  @Test
  public void configSettingRulesAreNotChecked() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make();
    scratch.file("hello/UCBUILD",
        "sh_library(",
        "    name = 'shlib',",
        "    srcs = select({",
        "        '//config:a': ['shlib.sh'],",
        "    }),",
        "    compatible_with = ['//buildenv/foo:a'])");
    useConfiguration("--define", "mode=a");
    assertNotNull(getConfiguredTarget("//hello:shlib"));
    assertNoEvents();
  }

  @Test
  public void fulfills() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo")
        .setEnvironments("a", "b")
        .setFulfills("a", "b")
        .setDefaults()
        .make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(constrainedTo("//buildenv/foo:a")),
        getDependingRule(constrainedTo("//buildenv/foo:b")));
    assertNotNull(getConfiguredTarget("//hello:main"));
    assertNoEvents();
  }

  @Test
  public void fulfillsIsNotSymmetric() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo")
        .setEnvironments("a", "b")
        .setFulfills("a", "b")
        .setDefaults()
        .make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(constrainedTo("//buildenv/foo:b")),
        getDependingRule(constrainedTo("//buildenv/foo:a")));
    reporter.removeHandler(failFastHandler);
    assertNull(getConfiguredTarget("//hello:main"));
    assertContainsEvent(
        "dependency //hello:dep doesn't support expected environment: //buildenv/foo:a");
  }

  @Test
  public void fulfillsIsTransitive() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo")
        .setEnvironments("a", "b", "c")
        .setFulfills("a", "b")
        .setFulfills("b", "c")
        .setDefaults()
        .make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(constrainedTo("//buildenv/foo:a")),
        getDependingRule(constrainedTo("//buildenv/foo:c")));
    assertNotNull(getConfiguredTarget("//hello:main"));
    assertNoEvents();
  }

  @Test
  public void defaultEnvironmentDirectlyFulfills() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo")
        .setEnvironments("a", "b")
        .setFulfills("a", "b")
        .setDefaults("a")
        .make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(),
        getDependingRule(constrainedTo("//buildenv/foo:b")));
    assertNotNull(getConfiguredTarget("//hello:main"));
    assertNoEvents();
  }

  @Test
  public void defaultEnvironmentIndirectlyFulfills() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo")
        .setEnvironments("a", "b", "c")
        .setFulfills("a", "b")
        .setFulfills("b", "c")
        .setDefaults("a")
        .make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(),
        getDependingRule(constrainedTo("//buildenv/foo:c")));
    assertNotNull(getConfiguredTarget("//hello:main"));
    assertNoEvents();
  }

  @Test
  public void environmentFulfillsExpectedDefault() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo")
        .setEnvironments("a", "b")
        .setFulfills("a", "b")
        .setDefaults("b")
        .make();
    scratch.file("hello/UCBUILD",
        getDependencyRule(constrainedTo("//buildenv/foo:a")),
        getDependingRule());
    assertNotNull(getConfiguredTarget("//hello:main"));
    assertNoEvents();
  }

  @Test
  public void constraintExemptRulesDontHaveConstraintAttributes() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo")
        .setEnvironments("a", "b")
        .setDefaults("a")
        .make();
    scratch.file("ihave/UCBUILD",
        "totally_free_rule(",
        "    name = 'nolimits',",
        "    restricted_to = ['//buildenv/foo:b']",
        ")");

    reporter.removeHandler(failFastHandler);
    assertNull(getConfiguredTarget("//ihave:nolimits"));
    assertContainsEvent("no such attribute 'restricted_to' in 'totally_free_rule'");
  }

  @Test
  public void buildingEnvironmentGroupDirectlyDoesntCrash() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo")
        .setEnvironments("a", "b")
        .setDefaults("a")
        .make();
    assertNotNull(getConfiguredTarget("//buildenv/foo:foo"));
  }

  private void writeDepsForSelectTests() throws Exception {
    scratch.file("deps/UCBUILD",
        "cc_library(",
        "    name = 'dep_a',",
        "    srcs = [],",
        "    restricted_to = ['//buildenv/foo:a'])",
        "cc_library(",
        "    name = 'dep_b',",
        "    srcs = [],",
        "    restricted_to = ['//buildenv/foo:b'])");
  }

  @Test
  public void selectableDepsCanMissEnvironments() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make();
    writeDepsForSelectTests();
    scratch.file("hello/UCBUILD",
        "cc_library(",
        "    name = 'lib',",
        "    srcs = [],",
        "    deps = select({",
        "        '//config:a': ['//deps:dep_a'],",
        "        '//config:b': ['//deps:dep_b'],",
        "    }),",
        "    compatible_with = ['//buildenv/foo:a', '//buildenv/foo:b'])");
    useConfiguration("--define", "mode=a");
    assertNotNull(getConfiguredTarget("//hello:lib"));
  }

  @Test
  public void staticCheckingOnSelectsTemporarilyDisabled() throws Exception {
    // TODO(bazel-team): update this test once static checking on selects is implemented. When
    // that happens, the union of all deps in the select must support the environments in the
    // depending rule. So the logic here is constraint-invalid because //buildenv/foo:c isn't
    // fulfilled by any of the deps.
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults().make();
    writeDepsForSelectTests();
    scratch.file("hello/UCBUILD",
        "cc_library(",
        "    name = 'lib',",
        "    srcs = [],",
        "    deps = select({",
        "        '//config:a': ['//deps:dep_a'],",
        "        '//config:b': ['//deps:dep_b'],",
        "    }),",
        "    compatible_with = ['//buildenv/foo:a', '//buildenv/foo:b', '//buildenv/foo:c'])");
    useConfiguration("--define", "mode=a");
    assertNotNull(getConfiguredTarget("//hello:lib"));
  }

  @Test
  public void depInBothSelectAndUnconditionalListIsAlwaysChecked() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make();
    writeDepsForSelectTests();
    scratch.file("hello/UCBUILD",
        "cc_library(",
        "    name = 'lib',",
        "    srcs = [],",
        "    deps = select({",
        "        '//config:a': ['//deps:dep_a'],",
        "        '//config:b': ['//deps:dep_b'],",
        "    }),",
        "    data = ['//deps:dep_a'],",
        "    compatible_with = ['//buildenv/foo:a', '//buildenv/foo:b'])");
    useConfiguration("--define", "mode=a");
    reporter.removeHandler(failFastHandler);
    assertNull(getConfiguredTarget("//hello:lib"));
    assertContainsEvent(
        "dependency //deps:dep_a doesn't support expected environment: //buildenv/foo:b");
  }

  @Test
  public void unconditionalSelectsAlwaysChecked() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make();
    writeDepsForSelectTests();
    scratch.file("hello/UCBUILD",
        "cc_library(",
        "    name = 'lib',",
        "    srcs = [],",
        "    deps = select({",
        "        '//conditions:default': ['//deps:dep_a'],",
        "    }),",
        "    compatible_with = ['//buildenv/foo:a', '//buildenv/foo:b'])");
    reporter.removeHandler(failFastHandler);
    assertNull(getConfiguredTarget("//hello:lib"));
    assertContainsEvent(
        "dependency //deps:dep_a doesn't support expected environment: //buildenv/foo:b");
  }

  @Test
  public void refinedEnvironmentCheckingValidCaseDirect() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make();
    writeDepsForSelectTests();
    scratch.file("hello/UCBUILD",
        "cc_library(",
        "    name = 'lib',",
        "    srcs = [],",
        "    deps = select({",
        "        '//config:a': ['//deps:dep_a'],",
        "        '//config:b': ['//deps:dep_b'],",
        "    }),",
        "    compatible_with = ['//buildenv/foo:a'])");
    useConfiguration("--define", "mode=a");
    // Valid because "--define mode=a" refines :lib to "compatible_with = ['//buildenv/foo:a']".
    assertNotNull(getConfiguredTarget("//hello:lib"));
  }

  @Test
  public void refinedEnvironmentCheckingBadCaseDirect() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make();
    writeDepsForSelectTests();
    scratch.file("hello/UCBUILD",
        "cc_library(",
        "    name = 'lib',",
        "    srcs = [],",
        "    deps = select({",
        "        '//config:a': ['//deps:dep_a'],",
        "        '//config:b': ['//deps:dep_b'],",
        "    }),",
        "    compatible_with = ['//buildenv/foo:b'])");
    useConfiguration("--define", "mode=a");
    reporter.removeHandler(failFastHandler);
    // Invalid because "--define mode=a" refines :lib to "compatible_with = []" (empty).
    assertNull(getConfiguredTarget("//hello:lib"));
    assertContainsEvent("//hello:lib: the current command-line flags disqualify all supported "
        + "environments because of incompatible select() paths:\n"
        + " environment: //buildenv/foo:b removed by: //hello:lib (/workspace/hello/UCBUILD:1:1)");
  }

  @Test
  public void refinedEnvironmentCheckingValidCaseTransitive() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make();
    writeDepsForSelectTests();
    scratch.file("hello/UCBUILD",
        "cc_library(",
        "    name = 'lib',",
        "    srcs = [],",
        "    deps = select({",
        "        '//config:a': ['//deps:dep_a'],",
        "        '//config:b': ['//deps:dep_b'],",
        "    }),",
        "    compatible_with = ['//buildenv/foo:a', '//buildenv/foo:b'])",
        "cc_library(",
        "    name = 'depender',",
        "    srcs = [],",
        "    deps = [':lib'],",
        "    compatible_with = ['//buildenv/foo:a'])");
    useConfiguration("--define", "mode=a");
    // Valid because "--define mode=a" refines :lib to "compatible_with = ['//buildenv/foo:a']".
    assertNotNull(getConfiguredTarget("//hello:depender"));
  }

  @Test
  public void refinedEnvironmentCheckingBadCaseTransitive() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make();
    writeDepsForSelectTests();
    scratch.file("hello/UCBUILD",
        "cc_library(",
        "    name = 'lib',",
        "    srcs = [],",
        "    deps = select({",
        "        '//config:a': ['//deps:dep_a'],",
        "        '//config:b': ['//deps:dep_b'],",
        "    }),",
        "    compatible_with = ['//buildenv/foo:a', '//buildenv/foo:b'])",
        "cc_library(",
        "    name = 'depender',",
        "    srcs = [],",
        "    deps = [':lib'],",
        "    compatible_with = ['//buildenv/foo:b'])");
    useConfiguration("--define", "mode=a");
    reporter.removeHandler(failFastHandler);
    // Invalid because "--define mode=a" refines :lib to "compatible_with = ['//buildenv/foo:a']".
    assertNull(getConfiguredTarget("//hello:depender"));
    assertContainsEvent("//hello:depender: the current command-line flags disqualify all supported "
        + "environments because of incompatible select() paths:\n"
        + " environment: //buildenv/foo:b removed by: //hello:lib (/workspace/hello/UCBUILD:1:1)");
  }

  @Test
  public void refinedEnvironmentCheckingBadCaseChooseLowestLevelCulprit() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make();
    writeDepsForSelectTests();
    scratch.file("hello/UCBUILD",
        "cc_library(",
        "    name = 'lib2',",  // Even though both lib1 and lib2 refine away b, lib2 is the culprit.
        "    srcs = [],",
        "    deps = select({",
        "        '//config:a': ['//deps:dep_a'],",
        "        '//config:b': ['//deps:dep_b'],",
        "    }),",
        "    compatible_with = ['//buildenv/foo:a', '//buildenv/foo:b'])",
        "cc_library(",
        "    name = 'lib1',",
        "    srcs = [],",
        "    deps = select({",
        "        '//config:a': [':lib2'],",
        "        '//config:b': ['//deps:dep_b'],",
        "    }),",
        "    compatible_with = ['//buildenv/foo:a', '//buildenv/foo:b'])",
        "cc_library(",
        "    name = 'depender',",
        "    srcs = [],",
        "    deps = [':lib1'],",
        "    compatible_with = ['//buildenv/foo:b'])");
    useConfiguration("--define", "mode=a");
    reporter.removeHandler(failFastHandler);
    // Invalid because "--define mode=a" refines :lib to "compatible_with = ['//buildenv/foo:a']".
    assertNull(getConfiguredTarget("//hello:depender"));
    assertContainsEvent("//hello:depender: the current command-line flags disqualify all supported "
        + "environments because of incompatible select() paths:\n"
        + " environment: //buildenv/foo:b removed by: //hello:lib2 (/workspace/hello/UCBUILD:1:1)");
  }

  @Test
  public void environmentRefiningAccountsForImplicitDefaults() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("b").make();
    writeDepsForSelectTests();
    scratch.file("hello/UCBUILD",
        "cc_library(",
        "    name = 'lib',",
        "    srcs = [],",
        "    deps = select({",
        "        '//config:a': ['//deps:dep_a'],",
        "        '//config:b': ['//deps:dep_b'],",
        "    }))");
    useConfiguration("--define", "mode=a");
    reporter.removeHandler(failFastHandler);
    // Invalid because :lib has an implicit default of ['//buildenv/foo:b'] and "--define mode=a"
    // refines it to "compatible_with = []" (empty).
    assertNull(getConfiguredTarget("//hello:lib"));
    assertContainsEvent("//hello:lib: the current command-line flags disqualify all supported "
        + "environments because of incompatible select() paths:\n"
        + " environment: //buildenv/foo:b removed by: //hello:lib (/workspace/hello/UCBUILD:1:1)");
  }

  @Test
  public void environmentRefiningChecksAllEnvironmentGroups() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make();
    new EnvironmentGroupMaker("buildenv/bar").setEnvironments("c", "d").setDefaults().make();
    scratch.file("deps/UCBUILD",
        "cc_library(",
        "    name = 'dep_a',",
        "    srcs = [],",
        "    restricted_to = ['//buildenv/foo:a', '//buildenv/bar:d'])",
        "cc_library(",
        "    name = 'dep_b',",
        "    srcs = [],",
        "    restricted_to = ['//buildenv/foo:b', '//buildenv/bar:c'])");
    scratch.file("hello/UCBUILD",
        "cc_library(",
        "    name = 'lib',",
        "    srcs = [],",
        "    deps = select({",
        "        '//config:a': ['//deps:dep_a'],",
        "        '//config:b': ['//deps:dep_b'],",
        "    }),",
        "    compatible_with = ['//buildenv/foo:a', '//buildenv/bar:c'])");
        useConfiguration("--define", "mode=a");
        reporter.removeHandler(failFastHandler);
        // Invalid because while the //buildenv/foo refinement successfully refines :lib to
        // ['//buildenv/foo:a'], the bar refinement refines it to [].
        assertNull(getConfiguredTarget("//hello:lib"));
    assertContainsEvent("//hello:lib: the current command-line flags disqualify all supported "
        + "environments because of incompatible select() paths:\n"
        + " environment: //buildenv/bar:c removed by: //hello:lib (/workspace/hello/UCBUILD:1:1)");
  }

  /**
   * When multiple environment groups get cleared out by refinement, batch the missing environments
   * by group membership.
   */
  @Test
  public void refinedEnvironmentCheckingPartitionsErrorsbyEnvironmentGroup() throws Exception {
    new EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make();
    new EnvironmentGroupMaker("buildenv/bar").setEnvironments("c", "d").setDefaults().make();
    scratch.file("hello/UCBUILD",
        "cc_library(",
        "    name = 'all_groups_gone',",
        "    srcs = [],",
        "    restricted_to = ['//buildenv/foo:b', '//buildenv/bar:d'])",
        "cc_library(",
        "    name = 'all_groups_there',",
        "    srcs = [],",
        "    restricted_to = ['//buildenv/foo:a', '//buildenv/bar:c'])",
        "cc_library(",
        "    name = 'lib',",
        "    srcs = [],",
        "    deps = select({",
        "        '//config:a': [':all_groups_gone'],",
        "        '//config:b': [':all_groups_there'],",
        "    }),",
        "    compatible_with = ['//buildenv/foo:a', '//buildenv/bar:c'])");
    useConfiguration("--define", "mode=a");
    reporter.removeHandler(failFastHandler);
    assertNull(getConfiguredTarget("//hello:lib"));
    assertContainsEvent("//hello:lib: the current command-line flags disqualify all supported "
        + "environments because of incompatible select() paths:\n"
        + "\nenvironment group: //buildenv/foo:foo:\n"
        + " environment: //buildenv/foo:a removed by: //hello:lib (/workspace/hello/UCBUILD:9:1)\n"
        + "\nenvironment group: //buildenv/bar:bar:\n"
        + " environment: //buildenv/bar:c removed by: //hello:lib (/workspace/hello/UCBUILD:9:1)");
  }
}

