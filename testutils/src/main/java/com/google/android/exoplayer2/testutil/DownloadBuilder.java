/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.google.android.exoplayer2.testutil;

import android.net.Uri;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.offline.Download;
import com.google.android.exoplayer2.offline.DownloadProgress;
import com.google.android.exoplayer2.offline.DownloadRequest;
import com.google.android.exoplayer2.offline.StreamKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Builder for {@link Download}.
 *
 * <p>Defines default values for each field (except {@code id}) to facilitate {@link Download}
 * creation for tests. Tests must avoid depending on the default values but explicitly set tested
 * parameters during test initialization.
 */
public final class DownloadBuilder {

  private final DownloadProgress progress;

  private String id;
  private Uri uri;
  @Nullable private String mimeType;
  private List<StreamKey> streamKeys;
  @Nullable private byte[] keySetId;
  @Nullable private String cacheKey;
  private byte[] customMetadata;

  @Download.State private int state;
  private long startTimeMs;
  private long updateTimeMs;
  private long contentLength;
  private int stopReason;
  private int failureReason;

  /**
   * Creates a download builder for "uri" and no stream keys.
   *
   * @param id The unique content identifier for the download.
   */
  public DownloadBuilder(String id) {
    this(
        id,
        Uri.parse("uri"),
        /* mimeType= */ null,
        /* streamKeys= */ Collections.emptyList(),
        /* keySetId= */ null,
        /* cacheKey= */ null,
        /* customMetadata= */ new byte[0]);
  }

  /**
   * Creates a download builder based on the attributes of the specified request.
   *
   * @param request A {@link DownloadRequest} defining the content to download.
   */
  public DownloadBuilder(DownloadRequest request) {
    this(
        request.id,
        request.uri,
        request.mimeType,
        request.streamKeys,
        request.keySetId,
        request.customCacheKey,
        request.data);
  }

  /** Creates a download builder. */
  private DownloadBuilder(
      String id,
      Uri uri,
      @Nullable String mimeType,
      List<StreamKey> streamKeys,
      @Nullable byte[] keySetId,
      @Nullable String cacheKey,
      byte[] customMetadata) {
    this.id = id;
    this.uri = uri;
    this.mimeType = mimeType;
    this.streamKeys = streamKeys;
    this.keySetId = keySetId;
    this.cacheKey = cacheKey;
    this.customMetadata = customMetadata;
    this.state = Download.STATE_QUEUED;
    this.contentLength = C.LENGTH_UNSET;
    this.failureReason = Download.FAILURE_REASON_NONE;
    this.progress = new DownloadProgress();
  }

  /** @see DownloadRequest#uri */
  public DownloadBuilder setUri(String uri) {
    this.uri = Uri.parse(uri);
    return this;
  }

  /** @see DownloadRequest#uri */
  public DownloadBuilder setUri(Uri uri) {
    this.uri = uri;
    return this;
  }

  /** @see DownloadRequest#mimeType */
  public DownloadBuilder setMimeType(String mimeType) {
    this.mimeType = mimeType;
    return this;
  }

  /** @see DownloadRequest#keySetId */
  public DownloadBuilder setKeySetId(byte[] keySetId) {
    this.keySetId = keySetId;
    return this;
  }

  /** @see DownloadRequest#customCacheKey */
  public DownloadBuilder setCacheKey(@Nullable String cacheKey) {
    this.cacheKey = cacheKey;
    return this;
  }

  /** @see Download#state */
  public DownloadBuilder setState(@Download.State int state) {
    this.state = state;
    return this;
  }

  /** @see DownloadProgress#percentDownloaded */
  public DownloadBuilder setPercentDownloaded(float percentDownloaded) {
    progress.percentDownloaded = percentDownloaded;
    return this;
  }

  /** @see DownloadProgress#bytesDownloaded */
  public DownloadBuilder setBytesDownloaded(long bytesDownloaded) {
    progress.bytesDownloaded = bytesDownloaded;
    return this;
  }

  /** @see Download#contentLength */
  public DownloadBuilder setContentLength(long contentLength) {
    this.contentLength = contentLength;
    return this;
  }

  /** @see Download#failureReason */
  public DownloadBuilder setFailureReason(int failureReason) {
    this.failureReason = failureReason;
    return this;
  }

  /** @see Download#stopReason */
  public DownloadBuilder setStopReason(int stopReason) {
    this.stopReason = stopReason;
    return this;
  }

  /** @see Download#startTimeMs */
  public DownloadBuilder setStartTimeMs(long startTimeMs) {
    this.startTimeMs = startTimeMs;
    return this;
  }

  /** @see Download#updateTimeMs */
  public DownloadBuilder setUpdateTimeMs(long updateTimeMs) {
    this.updateTimeMs = updateTimeMs;
    return this;
  }

  /** @see DownloadRequest#streamKeys */
  public DownloadBuilder setStreamKeys(StreamKey... streamKeys) {
    this.streamKeys = Arrays.asList(streamKeys);
    return this;
  }

  /** @see DownloadRequest#data */
  public DownloadBuilder setCustomMetadata(byte[] customMetadata) {
    this.customMetadata = customMetadata;
    return this;
  }

  public Download build() {
    DownloadRequest request =
        new DownloadRequest.Builder(id, uri)
            .setMimeType(mimeType)
            .setStreamKeys(streamKeys)
            .setKeySetId(keySetId)
            .setCustomCacheKey(cacheKey)
            .setData(customMetadata)
            .build();
    return new Download(
        request,
        state,
        startTimeMs,
        updateTimeMs,
        contentLength,
        stopReason,
        failureReason,
        progress);
  }
}
