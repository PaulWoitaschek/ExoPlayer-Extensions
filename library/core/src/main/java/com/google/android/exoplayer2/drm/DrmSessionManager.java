/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.google.android.exoplayer2.drm;

import android.os.Looper;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.Format;

/** Manages a DRM session. */
public interface DrmSessionManager {

  /** Returns {@link #DUMMY}. */
  static DrmSessionManager getDummyDrmSessionManager() {
    return DUMMY;
  }

  /** {@link DrmSessionManager} that supports no DRM schemes. */
  DrmSessionManager DUMMY =
      new DrmSessionManager() {

        @Override
        @Nullable
        public DrmSession acquireSession(
            Looper playbackLooper,
            @Nullable DrmSessionEventListener.EventDispatcher eventDispatcher,
            Format format) {
          if (format.drmInitData == null) {
            return null;
          } else {
            return new ErrorStateDrmSession(
                new DrmSession.DrmSessionException(
                    new UnsupportedDrmException(
                        UnsupportedDrmException.REASON_UNSUPPORTED_SCHEME)));
          }
        }

        @Override
        @Nullable
        public Class<UnsupportedMediaCrypto> getExoMediaCryptoType(Format format) {
          return format.drmInitData != null ? UnsupportedMediaCrypto.class : null;
        }
      };

  /**
   * Acquires any required resources.
   *
   * <p>{@link #release()} must be called to ensure the acquired resources are released. After
   * releasing, an instance may be re-prepared.
   */
  default void prepare() {
    // Do nothing.
  }

  /** Releases any acquired resources. */
  default void release() {
    // Do nothing.
  }

  /**
   * Returns a {@link DrmSession} for the specified {@link Format}, with an incremented reference
   * count. May return null if the {@link Format#drmInitData} is null and the DRM session manager is
   * not configured to attach a {@link DrmSession} to clear content. When the caller no longer needs
   * to use a returned {@link DrmSession}, it must call {@link
   * DrmSession#release(DrmSessionEventListener.EventDispatcher)} to decrement the reference count.
   *
   * <p>If the provided {@link Format} contains a null {@link Format#drmInitData}, the returned
   * {@link DrmSession} (if not null) will be a placeholder session which does not execute key
   * requests, and cannot be used to handle encrypted content. However, a placeholder session may be
   * used to configure secure decoders for playback of clear content periods, which can reduce the
   * cost of transitioning between clear and encrypted content.
   *
   * @param playbackLooper The looper associated with the media playback thread.
   * @param eventDispatcher The {@link DrmSessionEventListener.EventDispatcher} used to distribute
   *     events, and passed on to {@link
   *     DrmSession#acquire(DrmSessionEventListener.EventDispatcher)}.
   * @param format The {@link Format} for which to acquire a {@link DrmSession}.
   * @return The DRM session. May be null if the given {@link Format#drmInitData} is null.
   */
  @Nullable
  DrmSession acquireSession(
      Looper playbackLooper,
      @Nullable DrmSessionEventListener.EventDispatcher eventDispatcher,
      Format format);

  /**
   * Returns the {@link ExoMediaCrypto} type associated to sessions acquired for the given {@link
   * Format}. Returns the {@link UnsupportedMediaCrypto} type if this DRM session manager does not
   * support any of the DRM schemes defined in the given {@link Format}. Returns null if {@link
   * Format#drmInitData} is null and {@link #acquireSession} would return null for the given {@link
   * Format}.
   *
   * @param format The {@link Format} for which to return the {@link ExoMediaCrypto} type.
   * @return The {@link ExoMediaCrypto} type associated to sessions acquired using the given {@link
   *     Format}, or {@link UnsupportedMediaCrypto} if this DRM session manager does not support any
   *     of the DRM schemes defined in the given {@link Format}. May be null if {@link
   *     Format#drmInitData} is null and {@link #acquireSession} would return null for the given
   *     {@link Format}.
   */
  @Nullable
  Class<? extends ExoMediaCrypto> getExoMediaCryptoType(Format format);
}
