/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.transformer;

import android.opengl.Matrix;
import android.util.Size;

/**
 * Specifies a 4x4 transformation {@link Matrix} to apply in the vertex shader for each frame.
 *
 * <p>The matrix is applied to points given in normalized device coordinates (-1 to 1 on x, y, and z
 * axes). Transformed pixels that are moved outside of the normal device coordinate range are
 * clipped.
 *
 * <p>Output frame pixels outside of the transformed input frame will be black.
 */
public interface GlMatrixTransformation extends GlEffect {
  /**
   * Configures the input and output dimensions.
   *
   * <p>Must be called before {@link #getGlMatrixArray(long)}.
   *
   * @param inputWidth The input frame width, in pixels.
   * @param inputHeight The input frame height, in pixels.
   * @return The output frame {@link Size}, in pixels.
   */
  default Size configure(int inputWidth, int inputHeight) {
    return new Size(inputWidth, inputHeight);
  }

  /**
   * Returns the 4x4 transformation {@link Matrix} to apply to the frame with the given timestamp.
   */
  float[] getGlMatrixArray(long presentationTimeUs);

  @Override
  default SingleFrameGlTextureProcessor toGlTextureProcessor() {
    return new MatrixTransformationProcessor(this);
  }
}
