/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.openapi.util;

import com.intellij.openapi.Disposable;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class Disposer {
  private static final ObjectTree ourTree = new ObjectTree();

  public static boolean isDebugDisposerOn() {
    return "on".equals(System.getProperty("idea.disposer.debug"));
  }

  private static boolean ourDebugMode;

  private Disposer() {
  }

  @NotNull
  public static Disposable newDisposable() {
    // must not be lambda because we care about identity in ObjectTree.myObject2NodeMap
    return newDisposable(null);
  }

  @NotNull
  public static Disposable newDisposable(@Nullable String debugName) {
    // must not be lambda because we care about identity in ObjectTree.myObject2NodeMap
    return new Disposable() {
      @Override
      public void dispose() {
      }

      @Override
      public String toString() {
        return debugName == null ? super.toString() : debugName;
      }
    };
  }

  private static final Map<String, Disposable> ourKeyDisposables = ContainerUtil.createConcurrentWeakMap();

  public static void register(@NotNull Disposable parent, @NotNull Disposable child) {
    ourTree.register(parent, child);
  }

  public static void register(@NotNull Disposable parent, @NotNull Disposable child, @NonNls @NotNull final String key) {
    register(parent, child);
    Disposable v = get(key);
    if (v != null) throw new IllegalArgumentException("Key " + key + " already registered: " + v);
    ourKeyDisposables.put(key, child);
    register(child, new KeyDisposable(key));
  }

  private static class KeyDisposable implements Disposable {
    @NotNull
    private final String myKey;

    KeyDisposable(@NotNull String key) {myKey = key;}

    @Override
    public void dispose() {
      ourKeyDisposables.remove(myKey);
    }

    @Override
    public String toString() {
      return "KeyDisposable (" + myKey + ")";
    }
  }

  public static boolean isDisposed(@NotNull Disposable disposable) {
    return ourTree.getDisposalInfo(disposable) != null;
  }

  public static boolean isDisposing(@NotNull Disposable disposable) {
    return ourTree.isDisposing(disposable);
  }

  public static Disposable get(@NotNull String key) {
    return ourKeyDisposables.get(key);
  }

  public static void dispose(@NotNull Disposable disposable) {
    dispose(disposable, true);
  }

  public static void dispose(@NotNull Disposable disposable, boolean processUnregistered) {
    ourTree.executeAll(disposable, processUnregistered);
  }

  @NotNull
  public static ObjectTree getTree() {
    return ourTree;
  }

  public static void assertIsEmpty() {
    assertIsEmpty(false);
  }
  public static void assertIsEmpty(boolean throwError) {
    if (ourDebugMode) {
      ourTree.assertIsEmpty(throwError);
    }
  }

  /**
   * @return old value
   */
  public static boolean setDebugMode(boolean debugMode) {
    if (debugMode) {
      debugMode = !"off".equals(System.getProperty("idea.disposer.debug"));
    }
    boolean oldValue = ourDebugMode;
    ourDebugMode = debugMode;
    return oldValue;
  }

  public static boolean isDebugMode() {
    return ourDebugMode;
  }

  /**
   * @return object registered on parentDisposable which is equal to object, or null if not found
   */
  @Nullable
  public static <T extends Disposable> T findRegisteredObject(@NotNull Disposable parentDisposable, @NotNull T object) {
    return ourTree.findRegisteredObject(parentDisposable, object);
  }

  public static Throwable getDisposalTrace(@NotNull Disposable disposable) {
    return ObjectUtils.tryCast(getTree().getDisposalInfo(disposable), Throwable.class);
  }
}
