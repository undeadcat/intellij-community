// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.application.options;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.SimpleModificationTracker;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.codeStyle.modifier.CodeStyleSettingsModifier;
import com.intellij.psi.codeStyle.modifier.TransientCodeStyleSettings;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.util.ArrayUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

class CodeStyleCachedValueProvider implements CachedValueProvider<CodeStyleSettings> {
  private final static Logger LOG = Logger.getInstance(CodeStyleCachedValueProvider.class);

  private final static Key<CodeStyleCachedValueProvider> PROVIDER_KEY = Key.create("code.style.cached.value.provider");

  private final @NotNull PsiFile          myFile;
  private final @NotNull AsyncComputation myComputation;

  CodeStyleCachedValueProvider(@NotNull PsiFile file) {
    myFile = file;
    myComputation = new AsyncComputation();
  }

  @NotNull
  @Override
  public Result<CodeStyleSettings> compute() {
    CodeStyleSettings settings = myComputation.getCurrResult();
    logCached(myFile, settings);
    return new Result<>(settings, getDependencies(settings, myComputation));
  }

  @NotNull
  Object[] getDependencies(@NotNull CodeStyleSettings settings, @NotNull AsyncComputation computation) {
    List<Object> dependencies = new ArrayList<>();
    if (settings instanceof TransientCodeStyleSettings) {
      dependencies.addAll(((TransientCodeStyleSettings)settings).getDependencies());
    }
    else {
      dependencies.add(settings.getModificationTracker());
    }
    dependencies.add(computation.getTracker());
    return ArrayUtil.toObjectArray(dependencies);
  }

  static synchronized CodeStyleCachedValueProvider getInstance(@NotNull PsiFile file) {
    CodeStyleCachedValueProvider instance = file.getUserData(PROVIDER_KEY);
    if (instance == null) {
      instance = new CodeStyleCachedValueProvider(file);
      file.putUserData(PROVIDER_KEY, instance);
    }
    return instance;
  }

  private void notifyCachedValueComputed(@NotNull PsiFile file) {
    final Application application = ApplicationManager.getApplication();
    if (!application.isUnitTestMode()) {
      application.invokeLater(() -> {
        final CodeStyleSettingsManager settingsManager = CodeStyleSettingsManager.getInstance(file.getProject());
        settingsManager.fireCodeStyleSettingsChanged(file);
        myComputation.reset();
      });
    }
  }

  private static void logCached(@NotNull PsiFile file, @NotNull CodeStyleSettings settings) {
    LOG.debug(String.format(
      "File: %s (%s), cached: %s, tracker: %d", file.getName(), Integer.toHexString(file.hashCode()), settings,
      settings.getModificationTracker().getModificationCount()));
  }

  /**
   * Always contains some result which can be obtained by {@code getCurrResult()} method. Listeners are notified after
   * the computation is finished and {@code getCurrResult()} contains a stable computed value.
   */
  private class AsyncComputation {
    private final             AtomicBoolean             myIsActive = new AtomicBoolean();
    private volatile @NotNull CodeStyleSettings         myCurrResult;
    private final @NotNull    CodeStyleSettingsManager  mySettingsManager;
    private final             SimpleModificationTracker myTracker  = new SimpleModificationTracker();

    private AsyncComputation() {
      mySettingsManager = CodeStyleSettingsManager.getInstance(myFile.getProject());
      //noinspection deprecation
      myCurrResult = mySettingsManager.getCurrentSettings();
    }

    private void start() {
      final Application application = ApplicationManager.getApplication();
      Runnable computeSettingsInRA = () -> application.runReadAction(() -> computeSettings());
      if (!application.isUnitTestMode()) {
        application.executeOnPooledThread(computeSettingsInRA);
      }
      else {
        computeSettingsInRA.run();
        reset();
      }
    }

    private synchronized void computeSettings() {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Computation started for " + myFile.getName());
      }
      @SuppressWarnings("deprecation")
      CodeStyleSettings currSettings = mySettingsManager.getCurrentSettings();
      if (currSettings != mySettingsManager.getTemporarySettings()) {
        TransientCodeStyleSettings modifiableSettings = new TransientCodeStyleSettings(myFile, currSettings);
        for (CodeStyleSettingsModifier modifier : CodeStyleSettingsModifier.EP_NAME.getExtensionList()) {
          if (modifier.modifySettings(modifiableSettings, myFile)) {
            LOG.debug("Modifier: " + modifier.getClass().getName());
            modifiableSettings.setModifier(modifier);
            currSettings = modifiableSettings;
            break;
          }
        }
      }
      myCurrResult = currSettings;
      myTracker.incModificationCount();
      if (LOG.isDebugEnabled()) {
        LOG.debug("Computation ended for " + myFile.getName());
      }
      notifyCachedValueComputed(myFile);
    }

    @NotNull
    public CodeStyleSettings getCurrResult() {
      if (myIsActive.compareAndSet(false, true)) {
        start();
      }
      return myCurrResult;
    }

    private SimpleModificationTracker getTracker() {
      return myTracker;
    }

    void reset() {
      myIsActive.set(false);
    }
  }
}
