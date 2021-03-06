/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.siyeh.ig.controlflow;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.siyeh.ig.LightJavaInspectionTestCase;

/**
 * @author Bas Leijdekkers
 */
public class TrivialIfInspectionTest extends LightJavaInspectionTestCase {

  public void testParenthesesReturn() {
    doMemberTest("boolean b(int[] array) {" +
                 "  /*'if' statement can be simplified*/if/**/ (array.length == 10) {" +
                 "    return (true);" +
                 "  } else{" +
                 "    return false;" +
                 "  }" +
                 "}");
  }

  public void testParenthesesReturnNestedIf() {
    doMemberTest("\n" +
                 "  boolean b(int[] array) {\n" +
                 "    if (array != null) {\n" +
                 "      int len = array.length;\n" +
                 "      /*'if' statement can be simplified*/if/**/(len == 10) return true;\n" +
                 "    }\n" +
                 "    return false;\n" +
                 "  }\n" +
                 "");
  }

  public void testParenthesesAssignment() {
    doMemberTest("void b(int[] array) {" +
                 "  boolean result;" +
                 "  /*'if' statement can be simplified*/if/**/ (array.length == 10) {" +
                 "    result = (true);" +
                 "  } else{" +
                 "    result = (((false)));" +
                 "  }" +
                 "}");
  }


  @Override
  protected InspectionProfileEntry getInspection() {
    return new TrivialIfInspection();
  }
}
