/*
 * Copyright 2021 The Android Open Source Project
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

/** A custom interface that determines the speed for media at specific timestamps. */
/* package */ interface SpeedProvider {

  /**
   * Provides the speed that the media should be played at, based on the timeUs.
   *
   * @param timeUs The timestamp of the media.
   * @return The speed that the media should be played at, based on the timeUs.
   */
  float getSpeed(long timeUs);
}
