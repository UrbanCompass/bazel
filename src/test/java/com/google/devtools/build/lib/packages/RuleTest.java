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

package com.google.devtools.build.lib.packages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.devtools.build.lib.events.Location.LineAndColumn;
import com.google.devtools.build.lib.events.util.EventCollectionApparatus;
import com.google.devtools.build.lib.packages.util.PackageFactoryApparatus;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.Path;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link Rule}.
 */
@RunWith(JUnit4.class)
public class RuleTest {
  private Scratch scratch = new Scratch("/workspace");
  private EventCollectionApparatus events = new EventCollectionApparatus();
  private PackageFactoryApparatus packages = new PackageFactoryApparatus(events.reporter());

  @Test
  public void testAttributeLocation() throws Exception {
    Path buildFile = scratch.file("x/UCBUILD",
        "cc_binary(name = 'x',",
        "          srcs = ['a', 'b', 'c'],",
        "          defines = ['-Da', '-Db'])");
    Package pkg = packages.createPackage("x", buildFile);
    Rule rule = pkg.getRule("x");

    assertEquals(new LineAndColumn(1, 1), rule.getLocation().getStartLineAndColumn());

    // Special "name" attribute always has same location as rule:
    assertEquals(new LineAndColumn(1, 1),
                 rule.getAttributeLocation("name").getStartLineAndColumn());

    // User-provided attributes have precise locations:
    assertEquals(new LineAndColumn(2, 18),
                 rule.getAttributeLocation("srcs").getStartLineAndColumn());
    assertEquals(new LineAndColumn(3, 21),
                 rule.getAttributeLocation("defines").getStartLineAndColumn());

    // Default attributes have same location as rule:
    assertEquals(new LineAndColumn(1, 1),
                 rule.getAttributeLocation("malloc").getStartLineAndColumn());

    // Attempts to locate non-existent attributes don't fail;
    // the rule location is returned:
    assertEquals(new LineAndColumn(1, 1),
                 rule.getAttributeLocation("no-such-attr").getStartLineAndColumn());
  }

  @Test
  public void testOutputNameError() throws Exception {
    events.setFailFast(false);
    Path buildFile = scratch.file("namecollide/UCBUILD",
        "genrule(name = 'hello_world',",
                "srcs = ['ignore_me.txt'],",
                "outs = ['message.txt', 'hello_world'],",
                "cmd  = 'echo \"Hello, world.\" >$(location message.txt)')");

    Package pkg = packages.createPackage("namecollide", buildFile);
    Rule genRule = pkg.getRule("hello_world");
    assertFalse(genRule.containsErrors()); // TODO: assertTrue
    events.assertContainsWarning("target 'hello_world' is both a rule and a file; please choose "
                               + "another name for the rule");
  }

  @Test
  public void testIsLocalTestRuleForLocalEquals1() throws Exception {
    Path buildFile = scratch.file("x/UCBUILD",
        "cc_test(name = 'y',",
        "          srcs = ['a'],",
        "          local = 0)",
        "cc_test(name = 'z',",
        "          srcs = ['a'],",
        "          local = 1)");
    Package pkg = packages.createPackage("x", buildFile);
    Rule y = pkg.getRule("y");
    assertFalse(TargetUtils.isLocalTestRule(y));
    Rule z = pkg.getRule("z");
    assertTrue(TargetUtils.isLocalTestRule(z));
  }

  @Test
  public void testDeprecation() throws Exception {
    Path buildFile = scratch.file("x/UCBUILD",
        "cc_test(name = 'y')",
        "cc_test(name = 'z', deprecation = 'Foo')");
    Package pkg = packages.createPackage("x", buildFile);
    Rule y = pkg.getRule("y");
    assertNull(TargetUtils.getDeprecation(y));
    Rule z = pkg.getRule("z");
    assertEquals("Foo", TargetUtils.getDeprecation(z));
  }

  @Test
  public void testVisibilityValid() throws Exception {
    Package pkg = packages.createPackage("x", scratch.file("x/UCBUILD",
        "cc_binary(name = 'pr',",
        "          visibility = ['//visibility:private'])",
        "cc_binary(name = 'pu',",
        "          visibility = ['//visibility:public'])",
        "cc_binary(name = 'cu',",
        "          visibility = ['//a:b'])"));

    assertEquals(ConstantRuleVisibility.PUBLIC,
        pkg.getRule("pu").getVisibility());
    assertEquals(ConstantRuleVisibility.PRIVATE,
        pkg.getRule("pr").getVisibility());
  }
}
