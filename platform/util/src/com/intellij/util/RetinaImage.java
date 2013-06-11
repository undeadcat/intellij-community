/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package com.intellij.util;

import com.intellij.openapi.util.SystemInfo;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * @author Anton Makeev
 * @author Konstantin Bulenkov
 */
public class RetinaImage {

  private static boolean isAppleJvm = SystemInfo.isAppleJvm();

  public static Image createFrom(Image image, final int scale, Component ourComponent) {
    int w = image.getWidth(ourComponent);
    int h = image.getHeight(ourComponent);

    Image hidpi = create(image, w / scale, h / scale, BufferedImage.TYPE_INT_ARGB);
    if (isAppleJvm) {
      Graphics2D g = (Graphics2D)hidpi.getGraphics();
      g.scale(1f / scale, 1f / scale);
      g.drawImage(image, 0, 0, null);
      g.dispose();
    }

    return hidpi;
  }

  public static BufferedImage create(final int width, int height, int type) {
    return create(null, width, height, type);
  }


  public static BufferedImage create(Image image, final int width, int height, int type) {
    return isAppleJvm ? AppleHiDPIScaledImage.create(width, height, type)
                      : new JBHiDPIScaledImage(image, width, height, type);
  }
}
