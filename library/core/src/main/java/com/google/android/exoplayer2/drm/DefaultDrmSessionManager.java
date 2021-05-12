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

import android.annotation.SuppressLint;
import android.media.ResourceBusyException;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.drm.DrmInitData.SchemeData;
import com.google.android.exoplayer2.drm.DrmSession.DrmSessionException;
import com.google.android.exoplayer2.drm.ExoMediaDrm.OnEventListener;
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy;
import com.google.android.exoplayer2.upstream.LoadErrorHandlingPolicy;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** A {@link DrmSessionManager} that supports playbacks using {@link ExoMediaDrm}. */
@RequiresApi(18)
public class DefaultDrmSessionManager implements DrmSessionManager {

  /**
   * Builder for {@link DefaultDrmSessionManager} instances.
   *
   * <p>See {@link #Builder} for the list of default values.
   */
  public static final class Builder {

    private final HashMap<String, String> keyRequestParameters;
    private UUID uuid;
    private ExoMediaDrm.Provider exoMediaDrmProvider;
    private boolean multiSession;
    private int[] useDrmSessionsForClearContentTrackTypes;
    private boolean playClearSamplesWithoutKeys;
    private LoadErrorHandlingPolicy loadErrorHandlingPolicy;
    private long sessionKeepaliveMs;

    /**
     * Creates a builder with default values. The default values are:
     *
     * <ul>
     *   <li>{@link #setKeyRequestParameters keyRequestParameters}: An empty map.
     *   <li>{@link #setUuidAndExoMediaDrmProvider UUID}: {@link C#WIDEVINE_UUID}.
     *   <li>{@link #setUuidAndExoMediaDrmProvider ExoMediaDrm.Provider}: {@link
     *       FrameworkMediaDrm#DEFAULT_PROVIDER}.
     *   <li>{@link #setMultiSession multiSession}: {@code false}.
     *   <li>{@link #setUseDrmSessionsForClearContent useDrmSessionsForClearContent}: No tracks.
     *   <li>{@link #setPlayClearSamplesWithoutKeys playClearSamplesWithoutKeys}: {@code false}.
     *   <li>{@link #setLoadErrorHandlingPolicy LoadErrorHandlingPolicy}: {@link
     *       DefaultLoadErrorHandlingPolicy}.
     * </ul>
     */
    public Builder() {
      keyRequestParameters = new HashMap<>();
      uuid = C.WIDEVINE_UUID;
      exoMediaDrmProvider = FrameworkMediaDrm.DEFAULT_PROVIDER;
      loadErrorHandlingPolicy = new DefaultLoadErrorHandlingPolicy();
      useDrmSessionsForClearContentTrackTypes = new int[0];
      sessionKeepaliveMs = DEFAULT_SESSION_KEEPALIVE_MS;
    }

    /**
     * Sets the key request parameters to pass as the last argument to {@link
     * ExoMediaDrm#getKeyRequest(byte[], List, int, HashMap)}. May be null if not parameters need to
     * be passed.
     *
     * <p>Custom data for PlayReady should be set under {@link #PLAYREADY_CUSTOM_DATA_KEY}.
     *
     * @param keyRequestParameters A map with parameters.
     * @return This builder.
     */
    public Builder setKeyRequestParameters(@Nullable Map<String, String> keyRequestParameters) {
      this.keyRequestParameters.clear();
      if (keyRequestParameters != null) {
        this.keyRequestParameters.putAll(keyRequestParameters);
      }
      return this;
    }

    /**
     * Sets the UUID of the DRM scheme and the {@link ExoMediaDrm.Provider} to use.
     *
     * @param uuid The UUID of the DRM scheme.
     * @param exoMediaDrmProvider The {@link ExoMediaDrm.Provider}.
     * @return This builder.
     */
    public Builder setUuidAndExoMediaDrmProvider(
        UUID uuid, ExoMediaDrm.Provider exoMediaDrmProvider) {
      this.uuid = Assertions.checkNotNull(uuid);
      this.exoMediaDrmProvider = Assertions.checkNotNull(exoMediaDrmProvider);
      return this;
    }

    /**
     * Sets whether this session manager is allowed to acquire multiple simultaneous sessions.
     *
     * <p>Users should pass false when a single key request will obtain all keys required to decrypt
     * the associated content. {@code multiSession} is required when content uses key rotation.
     *
     * @param multiSession Whether this session manager is allowed to acquire multiple simultaneous
     *     sessions.
     * @return This builder.
     */
    public Builder setMultiSession(boolean multiSession) {
      this.multiSession = multiSession;
      return this;
    }

    /**
     * Sets whether this session manager should attach {@link DrmSession DrmSessions} to the clear
     * sections of the media content.
     *
     * <p>Using {@link DrmSession DrmSessions} for clear content avoids the recreation of decoders
     * when transitioning between clear and encrypted sections of content.
     *
     * @param useDrmSessionsForClearContentTrackTypes The track types ({@link C#TRACK_TYPE_AUDIO}
     *     and/or {@link C#TRACK_TYPE_VIDEO}) for which to use a {@link DrmSession} regardless of
     *     whether the content is clear or encrypted.
     * @return This builder.
     * @throws IllegalArgumentException If {@code useDrmSessionsForClearContentTrackTypes} contains
     *     track types other than {@link C#TRACK_TYPE_AUDIO} and {@link C#TRACK_TYPE_VIDEO}.
     */
    public Builder setUseDrmSessionsForClearContent(
        int... useDrmSessionsForClearContentTrackTypes) {
      for (int trackType : useDrmSessionsForClearContentTrackTypes) {
        Assertions.checkArgument(
            trackType == C.TRACK_TYPE_VIDEO || trackType == C.TRACK_TYPE_AUDIO);
      }
      this.useDrmSessionsForClearContentTrackTypes =
          useDrmSessionsForClearContentTrackTypes.clone();
      return this;
    }

    /**
     * Sets whether clear samples within protected content should be played when keys for the
     * encrypted part of the content have yet to be loaded.
     *
     * @param playClearSamplesWithoutKeys Whether clear samples within protected content should be
     *     played when keys for the encrypted part of the content have yet to be loaded.
     * @return This builder.
     */
    public Builder setPlayClearSamplesWithoutKeys(boolean playClearSamplesWithoutKeys) {
      this.playClearSamplesWithoutKeys = playClearSamplesWithoutKeys;
      return this;
    }

    /**
     * Sets the {@link LoadErrorHandlingPolicy} for key and provisioning requests.
     *
     * @param loadErrorHandlingPolicy A {@link LoadErrorHandlingPolicy}.
     * @return This builder.
     */
    public Builder setLoadErrorHandlingPolicy(LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
      this.loadErrorHandlingPolicy = Assertions.checkNotNull(loadErrorHandlingPolicy);
      return this;
    }

    /**
     * Sets the time to keep {@link DrmSession DrmSessions} alive when they're not in use.
     *
     * <p>It can be useful to keep sessions alive during playback of short clear sections of media
     * (e.g. ad breaks) to avoid opening new DRM sessions (and re-requesting keys) at the transition
     * back into secure content. This assumes the secure sections before and after the clear section
     * are encrypted with the same keys.
     *
     * <p>Defaults to {@link #DEFAULT_SESSION_KEEPALIVE_MS}. Pass {@link C#TIME_UNSET} to disable
     * keep-alive.
     *
     * @param sessionKeepaliveMs The time to keep {@link DrmSession}s alive before fully releasing,
     *     in milliseconds. Must be &gt; 0 or {@link C#TIME_UNSET} to disable keep-alive.
     * @return This builder.
     */
    public Builder setSessionKeepaliveMs(long sessionKeepaliveMs) {
      Assertions.checkArgument(sessionKeepaliveMs > 0 || sessionKeepaliveMs == C.TIME_UNSET);
      this.sessionKeepaliveMs = sessionKeepaliveMs;
      return this;
    }

    /** Builds a {@link DefaultDrmSessionManager} instance. */
    public DefaultDrmSessionManager build(MediaDrmCallback mediaDrmCallback) {
      return new DefaultDrmSessionManager(
          uuid,
          exoMediaDrmProvider,
          mediaDrmCallback,
          keyRequestParameters,
          multiSession,
          useDrmSessionsForClearContentTrackTypes,
          playClearSamplesWithoutKeys,
          loadErrorHandlingPolicy,
          sessionKeepaliveMs);
    }
  }

  /**
   * Signals that the {@link Format#drmInitData} passed to {@link #acquireSession} does not contain
   * scheme data for the required UUID.
   */
  public static final class MissingSchemeDataException extends Exception {

    private MissingSchemeDataException(UUID uuid) {
      super("Media does not support uuid: " + uuid);
    }
  }

  /**
   * A key for specifying PlayReady custom data in the key request parameters passed to {@link
   * Builder#setKeyRequestParameters(Map)}.
   */
  public static final String PLAYREADY_CUSTOM_DATA_KEY = "PRCustomData";

  /**
   * Determines the action to be done after a session acquired. One of {@link #MODE_PLAYBACK},
   * {@link #MODE_QUERY}, {@link #MODE_DOWNLOAD} or {@link #MODE_RELEASE}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({MODE_PLAYBACK, MODE_QUERY, MODE_DOWNLOAD, MODE_RELEASE})
  public @interface Mode {}
  /**
   * Loads and refreshes (if necessary) a license for playback. Supports streaming and offline
   * licenses.
   */
  public static final int MODE_PLAYBACK = 0;
  /** Restores an offline license to allow its status to be queried. */
  public static final int MODE_QUERY = 1;
  /** Downloads an offline license or renews an existing one. */
  public static final int MODE_DOWNLOAD = 2;
  /** Releases an existing offline license. */
  public static final int MODE_RELEASE = 3;
  /** Number of times to retry for initial provisioning and key request for reporting error. */
  public static final int INITIAL_DRM_REQUEST_RETRY_COUNT = 3;
  /** Default value for {@link Builder#setSessionKeepaliveMs(long)}. */
  public static final long DEFAULT_SESSION_KEEPALIVE_MS = 5 * 60 * C.MILLIS_PER_SECOND;

  private static final String TAG = "DefaultDrmSessionMgr";

  private final UUID uuid;
  private final ExoMediaDrm.Provider exoMediaDrmProvider;
  private final MediaDrmCallback callback;
  private final HashMap<String, String> keyRequestParameters;
  private final boolean multiSession;
  private final int[] useDrmSessionsForClearContentTrackTypes;
  private final boolean playClearSamplesWithoutKeys;
  private final ProvisioningManagerImpl provisioningManagerImpl;
  private final LoadErrorHandlingPolicy loadErrorHandlingPolicy;
  private final ReferenceCountListenerImpl referenceCountListener;
  private final long sessionKeepaliveMs;

  private final List<DefaultDrmSession> sessions;
  private final List<DefaultDrmSession> provisioningSessions;
  private final Set<DefaultDrmSession> keepaliveSessions;

  private int prepareCallsCount;
  @Nullable private ExoMediaDrm exoMediaDrm;
  @Nullable private DefaultDrmSession placeholderDrmSession;
  @Nullable private DefaultDrmSession noMultiSessionDrmSession;
  @Nullable private Looper playbackLooper;
  private @MonotonicNonNull Handler sessionReleasingHandler;
  private int mode;
  @Nullable private byte[] offlineLicenseKeySetId;

  /* package */ volatile @Nullable MediaDrmHandler mediaDrmHandler;

  /**
   * @param uuid The UUID of the drm scheme.
   * @param exoMediaDrm An underlying {@link ExoMediaDrm} for use by the manager.
   * @param callback Performs key and provisioning requests.
   * @param keyRequestParameters An optional map of parameters to pass as the last argument to
   *     {@link ExoMediaDrm#getKeyRequest(byte[], List, int, HashMap)}. May be null.
   * @deprecated Use {@link Builder} instead.
   */
  @SuppressWarnings("deprecation")
  @Deprecated
  public DefaultDrmSessionManager(
      UUID uuid,
      ExoMediaDrm exoMediaDrm,
      MediaDrmCallback callback,
      @Nullable HashMap<String, String> keyRequestParameters) {
    this(
        uuid,
        exoMediaDrm,
        callback,
        keyRequestParameters == null ? new HashMap<>() : keyRequestParameters,
        /* multiSession= */ false,
        INITIAL_DRM_REQUEST_RETRY_COUNT);
  }

  /**
   * @param uuid The UUID of the drm scheme.
   * @param exoMediaDrm An underlying {@link ExoMediaDrm} for use by the manager.
   * @param callback Performs key and provisioning requests.
   * @param keyRequestParameters An optional map of parameters to pass as the last argument to
   *     {@link ExoMediaDrm#getKeyRequest(byte[], List, int, HashMap)}. May be null.
   * @param multiSession A boolean that specify whether multiple key session support is enabled.
   *     Default is false.
   * @deprecated Use {@link Builder} instead.
   */
  @SuppressWarnings("deprecation")
  @Deprecated
  public DefaultDrmSessionManager(
      UUID uuid,
      ExoMediaDrm exoMediaDrm,
      MediaDrmCallback callback,
      @Nullable HashMap<String, String> keyRequestParameters,
      boolean multiSession) {
    this(
        uuid,
        exoMediaDrm,
        callback,
        keyRequestParameters == null ? new HashMap<>() : keyRequestParameters,
        multiSession,
        INITIAL_DRM_REQUEST_RETRY_COUNT);
  }

  /**
   * @param uuid The UUID of the drm scheme.
   * @param exoMediaDrm An underlying {@link ExoMediaDrm} for use by the manager.
   * @param callback Performs key and provisioning requests.
   * @param keyRequestParameters An optional map of parameters to pass as the last argument to
   *     {@link ExoMediaDrm#getKeyRequest(byte[], List, int, HashMap)}. May be null.
   * @param multiSession A boolean that specify whether multiple key session support is enabled.
   *     Default is false.
   * @param initialDrmRequestRetryCount The number of times to retry for initial provisioning and
   *     key request before reporting error.
   * @deprecated Use {@link Builder} instead.
   */
  @Deprecated
  public DefaultDrmSessionManager(
      UUID uuid,
      ExoMediaDrm exoMediaDrm,
      MediaDrmCallback callback,
      @Nullable HashMap<String, String> keyRequestParameters,
      boolean multiSession,
      int initialDrmRequestRetryCount) {
    this(
        uuid,
        new ExoMediaDrm.AppManagedProvider(exoMediaDrm),
        callback,
        keyRequestParameters == null ? new HashMap<>() : keyRequestParameters,
        multiSession,
        /* useDrmSessionsForClearContentTrackTypes= */ new int[0],
        /* playClearSamplesWithoutKeys= */ false,
        new DefaultLoadErrorHandlingPolicy(initialDrmRequestRetryCount),
        DEFAULT_SESSION_KEEPALIVE_MS);
  }

  private DefaultDrmSessionManager(
      UUID uuid,
      ExoMediaDrm.Provider exoMediaDrmProvider,
      MediaDrmCallback callback,
      HashMap<String, String> keyRequestParameters,
      boolean multiSession,
      int[] useDrmSessionsForClearContentTrackTypes,
      boolean playClearSamplesWithoutKeys,
      LoadErrorHandlingPolicy loadErrorHandlingPolicy,
      long sessionKeepaliveMs) {
    Assertions.checkNotNull(uuid);
    Assertions.checkArgument(!C.COMMON_PSSH_UUID.equals(uuid), "Use C.CLEARKEY_UUID instead");
    this.uuid = uuid;
    this.exoMediaDrmProvider = exoMediaDrmProvider;
    this.callback = callback;
    this.keyRequestParameters = keyRequestParameters;
    this.multiSession = multiSession;
    this.useDrmSessionsForClearContentTrackTypes = useDrmSessionsForClearContentTrackTypes;
    this.playClearSamplesWithoutKeys = playClearSamplesWithoutKeys;
    this.loadErrorHandlingPolicy = loadErrorHandlingPolicy;
    provisioningManagerImpl = new ProvisioningManagerImpl();
    referenceCountListener = new ReferenceCountListenerImpl();
    mode = MODE_PLAYBACK;
    sessions = new ArrayList<>();
    provisioningSessions = new ArrayList<>();
    keepaliveSessions = Sets.newIdentityHashSet();
    this.sessionKeepaliveMs = sessionKeepaliveMs;
  }

  /**
   * Sets the mode, which determines the role of sessions acquired from the instance. This must be
   * called before {@link #acquireSession(Looper, DrmSessionEventListener.EventDispatcher, Format)}
   * is called.
   *
   * <p>By default, the mode is {@link #MODE_PLAYBACK} and a streaming license is requested when
   * required.
   *
   * <p>{@code mode} must be one of these:
   *
   * <ul>
   *   <li>{@link #MODE_PLAYBACK}: If {@code offlineLicenseKeySetId} is null then a streaming
   *       license is requested. Otherwise, the offline license is restored.
   *   <li>{@link #MODE_QUERY}: {@code offlineLicenseKeySetId} cannot be null. The offline license
   *       is restored to allow its status to be queried.
   *   <li>{@link #MODE_DOWNLOAD}: If {@code offlineLicenseKeySetId} is null then an offline license
   *       is requested. Otherwise, the offline license is renewed.
   *   <li>{@link #MODE_RELEASE}: {@code offlineLicenseKeySetId} cannot be null. The offline license
   *       is released.
   * </ul>
   *
   * @param mode The mode to be set.
   * @param offlineLicenseKeySetId The key set id of the license to be used with the given mode.
   */
  public void setMode(@Mode int mode, @Nullable byte[] offlineLicenseKeySetId) {
    Assertions.checkState(sessions.isEmpty());
    if (mode == MODE_QUERY || mode == MODE_RELEASE) {
      Assertions.checkNotNull(offlineLicenseKeySetId);
    }
    this.mode = mode;
    this.offlineLicenseKeySetId = offlineLicenseKeySetId;
  }

  // DrmSessionManager implementation.

  @Override
  public final void prepare() {
    if (prepareCallsCount++ != 0) {
      return;
    }
    Assertions.checkState(exoMediaDrm == null);
    exoMediaDrm = exoMediaDrmProvider.acquireExoMediaDrm(uuid);
    exoMediaDrm.setOnEventListener(new MediaDrmEventListener());
  }

  @Override
  public final void release() {
    if (--prepareCallsCount != 0) {
      return;
    }
    // Make a local copy, because sessions are removed from this.sessions during release (via
    // callback).
    List<DefaultDrmSession> sessions = new ArrayList<>(this.sessions);
    for (int i = 0; i < sessions.size(); i++) {
      // Release all the keepalive acquisitions.
      sessions.get(i).release(/* eventDispatcher= */ null);
    }
    Assertions.checkNotNull(exoMediaDrm).release();
    exoMediaDrm = null;
  }

  @Override
  @Nullable
  public DrmSession acquireSession(
      Looper playbackLooper,
      @Nullable DrmSessionEventListener.EventDispatcher eventDispatcher,
      Format format) {
    initPlaybackLooper(playbackLooper);
    maybeCreateMediaDrmHandler(playbackLooper);

    if (format.drmInitData == null) {
      // Content is not encrypted.
      return maybeAcquirePlaceholderSession(MimeTypes.getTrackType(format.sampleMimeType));
    }

    @Nullable List<SchemeData> schemeDatas = null;
    if (offlineLicenseKeySetId == null) {
      schemeDatas = getSchemeDatas(Assertions.checkNotNull(format.drmInitData), uuid, false);
      if (schemeDatas.isEmpty()) {
        final MissingSchemeDataException error = new MissingSchemeDataException(uuid);
        if (eventDispatcher != null) {
          eventDispatcher.drmSessionManagerError(error);
        }
        return new ErrorStateDrmSession(new DrmSessionException(error));
      }
    }

    @Nullable DefaultDrmSession session;
    if (!multiSession) {
      session = noMultiSessionDrmSession;
    } else {
      // Only use an existing session if it has matching init data.
      session = null;
      for (DefaultDrmSession existingSession : sessions) {
        if (Util.areEqual(existingSession.schemeDatas, schemeDatas)) {
          session = existingSession;
          break;
        }
      }
    }

    if (session == null) {
      // Create a new session.
      session =
          createAndAcquireSessionWithRetry(
              schemeDatas, /* isPlaceholderSession= */ false, eventDispatcher);
      if (!multiSession) {
        noMultiSessionDrmSession = session;
      }
      sessions.add(session);
    } else {
      session.acquire(eventDispatcher);
    }

    return session;
  }

  @Override
  @Nullable
  public Class<? extends ExoMediaCrypto> getExoMediaCryptoType(Format format) {
    Class<? extends ExoMediaCrypto> exoMediaCryptoType =
        Assertions.checkNotNull(exoMediaDrm).getExoMediaCryptoType();
    if (format.drmInitData == null) {
      int trackType = MimeTypes.getTrackType(format.sampleMimeType);
      return Util.linearSearch(useDrmSessionsForClearContentTrackTypes, trackType) != C.INDEX_UNSET
          ? exoMediaCryptoType
          : null;
    } else {
      return canAcquireSession(format.drmInitData)
          ? exoMediaCryptoType
          : UnsupportedMediaCrypto.class;
    }
  }

  // Internal methods.

  @Nullable
  private DrmSession maybeAcquirePlaceholderSession(int trackType) {
    ExoMediaDrm exoMediaDrm = Assertions.checkNotNull(this.exoMediaDrm);
    boolean avoidPlaceholderDrmSessions =
        FrameworkMediaCrypto.class.equals(exoMediaDrm.getExoMediaCryptoType())
            && FrameworkMediaCrypto.WORKAROUND_DEVICE_NEEDS_KEYS_TO_CONFIGURE_CODEC;
    // Avoid attaching a session to sparse formats.
    if (avoidPlaceholderDrmSessions
        || Util.linearSearch(useDrmSessionsForClearContentTrackTypes, trackType) == C.INDEX_UNSET
        || UnsupportedMediaCrypto.class.equals(exoMediaDrm.getExoMediaCryptoType())) {
      return null;
    }
    if (placeholderDrmSession == null) {
      DefaultDrmSession placeholderDrmSession =
          createAndAcquireSessionWithRetry(
              /* schemeDatas= */ ImmutableList.of(),
              /* isPlaceholderSession= */ true,
              /* eventDispatcher= */ null);
      sessions.add(placeholderDrmSession);
      this.placeholderDrmSession = placeholderDrmSession;
    } else {
      placeholderDrmSession.acquire(/* eventDispatcher= */ null);
    }
    return placeholderDrmSession;
  }

  private boolean canAcquireSession(DrmInitData drmInitData) {
    if (offlineLicenseKeySetId != null) {
      // An offline license can be restored so a session can always be acquired.
      return true;
    }
    List<SchemeData> schemeDatas = getSchemeDatas(drmInitData, uuid, true);
    if (schemeDatas.isEmpty()) {
      if (drmInitData.schemeDataCount == 1 && drmInitData.get(0).matches(C.COMMON_PSSH_UUID)) {
        // Assume scheme specific data will be added before the session is opened.
        Log.w(
            TAG, "DrmInitData only contains common PSSH SchemeData. Assuming support for: " + uuid);
      } else {
        // No data for this manager's scheme.
        return false;
      }
    }
    String schemeType = drmInitData.schemeType;
    if (schemeType == null || C.CENC_TYPE_cenc.equals(schemeType)) {
      // If there is no scheme information, assume patternless AES-CTR.
      return true;
    } else if (C.CENC_TYPE_cbcs.equals(schemeType)) {
      // Support for cbcs (AES-CBC with pattern encryption) was added in API 24. However, the
      // implementation was not stable until API 25.
      return Util.SDK_INT >= 25;
    } else if (C.CENC_TYPE_cbc1.equals(schemeType) || C.CENC_TYPE_cens.equals(schemeType)) {
      // Support for cbc1 (AES-CTR with pattern encryption) and cens (AES-CBC without pattern
      // encryption) was also added in API 24 and made stable from API 25, however support was
      // removed from API 30. Since the range of API levels for which these modes are usable is too
      // small to be useful, we don't indicate support on any API level.
      return false;
    }
    // Unknown schemes, assume one of them is supported.
    return true;
  }

  private void initPlaybackLooper(Looper playbackLooper) {
    if (this.playbackLooper == null) {
      this.playbackLooper = playbackLooper;
      this.sessionReleasingHandler = new Handler(playbackLooper);
    } else {
      Assertions.checkState(this.playbackLooper == playbackLooper);
    }
  }

  private void maybeCreateMediaDrmHandler(Looper playbackLooper) {
    if (mediaDrmHandler == null) {
      mediaDrmHandler = new MediaDrmHandler(playbackLooper);
    }
  }

  private DefaultDrmSession createAndAcquireSessionWithRetry(
      @Nullable List<SchemeData> schemeDatas,
      boolean isPlaceholderSession,
      @Nullable DrmSessionEventListener.EventDispatcher eventDispatcher) {
    DefaultDrmSession session =
        createAndAcquireSession(schemeDatas, isPlaceholderSession, eventDispatcher);
    if (session.getState() == DrmSession.STATE_ERROR
        && (Util.SDK_INT < 19
            || Assertions.checkNotNull(session.getError()).getCause()
                instanceof ResourceBusyException)) {
      // We're short on DRM session resources, so eagerly release all our keepalive sessions.
      // ResourceBusyException is only available at API 19, so on earlier versions we always
      // eagerly release regardless of the underlying error.
      if (!keepaliveSessions.isEmpty()) {
        // Make a local copy, because sessions are removed from this.timingOutSessions during
        // release (via callback).
        ImmutableList<DefaultDrmSession> timingOutSessions =
            ImmutableList.copyOf(this.keepaliveSessions);
        for (DrmSession timingOutSession : timingOutSessions) {
          timingOutSession.release(/* eventDispatcher= */ null);
        }
        // Undo the acquisitions from createAndAcquireSession().
        session.release(eventDispatcher);
        if (sessionKeepaliveMs != C.TIME_UNSET) {
          session.release(/* eventDispatcher= */ null);
        }
        session = createAndAcquireSession(schemeDatas, isPlaceholderSession, eventDispatcher);
      }
    }
    return session;
  }

  /**
   * Creates a new {@link DefaultDrmSession} and acquires it on behalf of the caller (passing in
   * {@code eventDispatcher}).
   *
   * <p>If {@link #sessionKeepaliveMs} != {@link C#TIME_UNSET} then acquires it again to allow the
   * manager to keep it alive (passing in {@code eventDispatcher=null}.
   */
  private DefaultDrmSession createAndAcquireSession(
      @Nullable List<SchemeData> schemeDatas,
      boolean isPlaceholderSession,
      @Nullable DrmSessionEventListener.EventDispatcher eventDispatcher) {
    Assertions.checkNotNull(exoMediaDrm);
    // Placeholder sessions should always play clear samples without keys.
    boolean playClearSamplesWithoutKeys = this.playClearSamplesWithoutKeys | isPlaceholderSession;
    DefaultDrmSession session =
        new DefaultDrmSession(
            uuid,
            exoMediaDrm,
            /* provisioningManager= */ provisioningManagerImpl,
            referenceCountListener,
            schemeDatas,
            mode,
            playClearSamplesWithoutKeys,
            isPlaceholderSession,
            offlineLicenseKeySetId,
            keyRequestParameters,
            callback,
            Assertions.checkNotNull(playbackLooper),
            loadErrorHandlingPolicy);
    // Acquire the session once on behalf of the caller to DrmSessionManager - this is the
    // reference 'assigned' to the caller which they're responsible for releasing. Do this first,
    // to ensure that eventDispatcher receives all events related to the initial
    // acquisition/opening.
    session.acquire(eventDispatcher);
    if (sessionKeepaliveMs != C.TIME_UNSET) {
      // Acquire the session once more so the Manager can keep it alive.
      session.acquire(/* eventDispatcher= */ null);
    }
    return session;
  }

  /**
   * Extracts {@link SchemeData} instances suitable for the given DRM scheme {@link UUID}.
   *
   * @param drmInitData The {@link DrmInitData} from which to extract the {@link SchemeData}.
   * @param uuid The UUID.
   * @param allowMissingData Whether a {@link SchemeData} with null {@link SchemeData#data} may be
   *     returned.
   * @return The extracted {@link SchemeData} instances, or an empty list if no suitable data is
   *     present.
   */
  private static List<SchemeData> getSchemeDatas(
      DrmInitData drmInitData, UUID uuid, boolean allowMissingData) {
    // Look for matching scheme data (matching the Common PSSH box for ClearKey).
    List<SchemeData> matchingSchemeDatas = new ArrayList<>(drmInitData.schemeDataCount);
    for (int i = 0; i < drmInitData.schemeDataCount; i++) {
      SchemeData schemeData = drmInitData.get(i);
      boolean uuidMatches =
          schemeData.matches(uuid)
              || (C.CLEARKEY_UUID.equals(uuid) && schemeData.matches(C.COMMON_PSSH_UUID));
      if (uuidMatches && (schemeData.data != null || allowMissingData)) {
        matchingSchemeDatas.add(schemeData);
      }
    }
    return matchingSchemeDatas;
  }

  @SuppressLint("HandlerLeak")
  private class MediaDrmHandler extends Handler {

    public MediaDrmHandler(Looper looper) {
      super(looper);
    }

    @Override
    public void handleMessage(Message msg) {
      byte[] sessionId = (byte[]) msg.obj;
      if (sessionId == null) {
        // The event is not associated with any particular session.
        return;
      }
      for (DefaultDrmSession session : sessions) {
        if (session.hasSessionId(sessionId)) {
          session.onMediaDrmEvent(msg.what);
          return;
        }
      }
    }
  }

  private class ProvisioningManagerImpl implements DefaultDrmSession.ProvisioningManager {
    @Override
    public void provisionRequired(DefaultDrmSession session) {
      if (provisioningSessions.contains(session)) {
        // The session has already requested provisioning.
        return;
      }
      provisioningSessions.add(session);
      if (provisioningSessions.size() == 1) {
        // This is the first session requesting provisioning, so have it perform the operation.
        session.provision();
      }
    }

    @Override
    public void onProvisionCompleted() {
      for (DefaultDrmSession session : provisioningSessions) {
        session.onProvisionCompleted();
      }
      provisioningSessions.clear();
    }

    @Override
    public void onProvisionError(Exception error) {
      for (DefaultDrmSession session : provisioningSessions) {
        session.onProvisionError(error);
      }
      provisioningSessions.clear();
    }
  }

  private class ReferenceCountListenerImpl implements DefaultDrmSession.ReferenceCountListener {

    @Override
    public void onReferenceCountIncremented(DefaultDrmSession session, int newReferenceCount) {
      if (sessionKeepaliveMs != C.TIME_UNSET) {
        // The session has been acquired elsewhere so we want to cancel our timeout.
        keepaliveSessions.remove(session);
        Assertions.checkNotNull(sessionReleasingHandler).removeCallbacksAndMessages(session);
      }
    }

    @Override
    public void onReferenceCountDecremented(DefaultDrmSession session, int newReferenceCount) {
      if (newReferenceCount == 1 && sessionKeepaliveMs != C.TIME_UNSET) {
        // Only the internal keep-alive reference remains, so we can start the timeout.
        keepaliveSessions.add(session);
        Assertions.checkNotNull(sessionReleasingHandler)
            .postAtTime(
                () -> session.release(/* eventDispatcher= */ null),
                session,
                /* uptimeMillis= */ SystemClock.uptimeMillis() + sessionKeepaliveMs);
      } else if (newReferenceCount == 0) {
        // This session is fully released.
        sessions.remove(session);
        if (placeholderDrmSession == session) {
          placeholderDrmSession = null;
        }
        if (noMultiSessionDrmSession == session) {
          noMultiSessionDrmSession = null;
        }
        if (provisioningSessions.size() > 1 && provisioningSessions.get(0) == session) {
          // Other sessions were waiting for the released session to complete a provision operation.
          // We need to have one of those sessions perform the provision operation instead.
          provisioningSessions.get(1).provision();
        }
        provisioningSessions.remove(session);
        if (sessionKeepaliveMs != C.TIME_UNSET) {
          Assertions.checkNotNull(sessionReleasingHandler).removeCallbacksAndMessages(session);
          keepaliveSessions.remove(session);
        }
      }
    }
  }

  private class MediaDrmEventListener implements OnEventListener {

    @Override
    public void onEvent(
        ExoMediaDrm md, @Nullable byte[] sessionId, int event, int extra, @Nullable byte[] data) {
      Assertions.checkNotNull(mediaDrmHandler).obtainMessage(event, sessionId).sendToTarget();
    }
  }
}
