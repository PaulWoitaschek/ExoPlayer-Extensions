/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.google.android.exoplayer2.analytics;

import static com.google.android.exoplayer2.util.Assertions.checkNotNull;

import android.os.Looper;
import android.util.SparseArray;
import android.view.Surface;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Player.DiscontinuityReason;
import com.google.android.exoplayer2.Player.PlaybackSuppressionReason;
import com.google.android.exoplayer2.Player.TimelineChangeReason;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.decoder.DecoderReuseEvaluation;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.source.LoadEventInfo;
import com.google.android.exoplayer2.source.MediaLoadData;
import com.google.android.exoplayer2.source.MediaSource.MediaPeriodId;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.util.MutableFlags;
import com.google.common.base.Objects;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * A listener for analytics events.
 *
 * <p>All events are recorded with an {@link EventTime} specifying the elapsed real time and media
 * time at the time of the event.
 *
 * <p>All methods have no-op default implementations to allow selective overrides.
 *
 * <p>Listeners can choose to implement individual events (e.g. {@link
 * #onIsPlayingChanged(EventTime, boolean)}) or {@link #onEvents(Player, Events)}, which is called
 * after one or more events occurred together.
 */
public interface AnalyticsListener {

  /** A set of {@link EventFlags}. */
  final class Events extends MutableFlags {

    private final SparseArray<EventTime> eventTimes;

    /** Creates the set of event flags. */
    public Events() {
      eventTimes = new SparseArray<>(/* initialCapacity= */ 0);
    }

    /**
     * Returns the {@link EventTime} for the specified event.
     *
     * @param event The {@link EventFlags event}.
     * @return The {@link EventTime} of this event.
     */
    public EventTime getEventTime(@EventFlags int event) {
      return checkNotNull(eventTimes.get(event));
    }

    /**
     * Sets the {@link EventTime} values for events recorded in this set.
     *
     * @param eventTimes A map from {@link EventFlags} to {@link EventTime}. Must at least contain
     *     all the events recorded in this set.
     */
    public void setEventTimes(SparseArray<EventTime> eventTimes) {
      this.eventTimes.clear();
      for (int i = 0; i < size(); i++) {
        @EventFlags int eventFlag = get(i);
        this.eventTimes.append(eventFlag, checkNotNull(eventTimes.get(eventFlag)));
      }
    }

    /**
     * Returns whether the given event occurred.
     *
     * @param event The {@link EventFlags event}.
     * @return Whether the event occurred.
     */
    @Override
    public boolean contains(@EventFlags int event) {
      // Overridden to add IntDef compiler enforcement and new JavaDoc.
      return super.contains(event);
    }

    /**
     * Returns whether any of the given events occurred.
     *
     * @param events The {@link EventFlags events}.
     * @return Whether any of the events occurred.
     */
    @Override
    public boolean containsAny(@EventFlags int... events) {
      // Overridden to add IntDef compiler enforcement and new JavaDoc.
      return super.containsAny(events);
    }

    /**
     * Returns the {@link EventFlags event} at the given index.
     *
     * <p>Although index-based access is possible, it doesn't imply a particular order of these
     * events.
     *
     * @param index The index. Must be between 0 (inclusive) and {@link #size()} (exclusive).
     * @return The {@link EventFlags event} at the given index.
     */
    @Override
    @EventFlags
    public int get(int index) {
      // Overridden to add IntDef compiler enforcement and new JavaDoc.
      return super.get(index);
    }
  }

  /**
   * Events that can be reported via {@link #onEvents(Player, Events)}.
   *
   * <p>One of the {@link AnalyticsListener}{@code .EVENT_*} flags.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
    EVENT_TIMELINE_CHANGED,
    EVENT_MEDIA_ITEM_TRANSITION,
    EVENT_TRACKS_CHANGED,
    EVENT_STATIC_METADATA_CHANGED,
    EVENT_IS_LOADING_CHANGED,
    EVENT_PLAYBACK_STATE_CHANGED,
    EVENT_PLAY_WHEN_READY_CHANGED,
    EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
    EVENT_IS_PLAYING_CHANGED,
    EVENT_REPEAT_MODE_CHANGED,
    EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
    EVENT_PLAYER_ERROR,
    EVENT_POSITION_DISCONTINUITY,
    EVENT_PLAYBACK_PARAMETERS_CHANGED,
    EVENT_LOAD_STARTED,
    EVENT_LOAD_COMPLETED,
    EVENT_LOAD_CANCELED,
    EVENT_LOAD_ERROR,
    EVENT_DOWNSTREAM_FORMAT_CHANGED,
    EVENT_UPSTREAM_DISCARDED,
    EVENT_BANDWIDTH_ESTIMATE,
    EVENT_METADATA,
    EVENT_AUDIO_ENABLED,
    EVENT_AUDIO_DECODER_INITIALIZED,
    EVENT_AUDIO_INPUT_FORMAT_CHANGED,
    EVENT_AUDIO_POSITION_ADVANCING,
    EVENT_AUDIO_UNDERRUN,
    EVENT_AUDIO_DECODER_RELEASED,
    EVENT_AUDIO_DISABLED,
    EVENT_AUDIO_SESSION_ID,
    EVENT_AUDIO_ATTRIBUTES_CHANGED,
    EVENT_SKIP_SILENCE_ENABLED_CHANGED,
    EVENT_AUDIO_SINK_ERROR,
    EVENT_VOLUME_CHANGED,
    EVENT_VIDEO_ENABLED,
    EVENT_VIDEO_DECODER_INITIALIZED,
    EVENT_VIDEO_INPUT_FORMAT_CHANGED,
    EVENT_DROPPED_VIDEO_FRAMES,
    EVENT_VIDEO_DECODER_RELEASED,
    EVENT_VIDEO_DISABLED,
    EVENT_VIDEO_FRAME_PROCESSING_OFFSET,
    EVENT_RENDERED_FIRST_FRAME,
    EVENT_VIDEO_SIZE_CHANGED,
    EVENT_SURFACE_SIZE_CHANGED,
    EVENT_DRM_SESSION_ACQUIRED,
    EVENT_DRM_KEYS_LOADED,
    EVENT_DRM_SESSION_MANAGER_ERROR,
    EVENT_DRM_KEYS_RESTORED,
    EVENT_DRM_KEYS_REMOVED,
    EVENT_DRM_SESSION_RELEASED,
    EVENT_PLAYER_RELEASED,
  })
  @interface EventFlags {}
  /** {@link Player#getCurrentTimeline()} changed. */
  int EVENT_TIMELINE_CHANGED = Player.EVENT_TIMELINE_CHANGED;
  /**
   * {@link Player#getCurrentMediaItem()} changed or the player started repeating the current item.
   */
  int EVENT_MEDIA_ITEM_TRANSITION = Player.EVENT_MEDIA_ITEM_TRANSITION;
  /**
   * {@link Player#getCurrentTrackGroups()} or {@link Player#getCurrentTrackSelections()} changed.
   */
  int EVENT_TRACKS_CHANGED = Player.EVENT_TRACKS_CHANGED;
  /** {@link Player#getCurrentStaticMetadata()} changed. */
  int EVENT_STATIC_METADATA_CHANGED = Player.EVENT_STATIC_METADATA_CHANGED;
  /** {@link Player#isLoading()} ()} changed. */
  int EVENT_IS_LOADING_CHANGED = Player.EVENT_IS_LOADING_CHANGED;
  /** {@link Player#getPlaybackState()} changed. */
  int EVENT_PLAYBACK_STATE_CHANGED = Player.EVENT_PLAYBACK_STATE_CHANGED;
  /** {@link Player#getPlayWhenReady()} changed. */
  int EVENT_PLAY_WHEN_READY_CHANGED = Player.EVENT_PLAY_WHEN_READY_CHANGED;
  /** {@link Player#getPlaybackSuppressionReason()} changed. */
  int EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED = Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED;
  /** {@link Player#isPlaying()} changed. */
  int EVENT_IS_PLAYING_CHANGED = Player.EVENT_IS_PLAYING_CHANGED;
  /** {@link Player#getRepeatMode()} changed. */
  int EVENT_REPEAT_MODE_CHANGED = Player.EVENT_REPEAT_MODE_CHANGED;
  /** {@link Player#getShuffleModeEnabled()} changed. */
  int EVENT_SHUFFLE_MODE_ENABLED_CHANGED = Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED;
  /** {@link Player#getPlayerError()} changed. */
  int EVENT_PLAYER_ERROR = Player.EVENT_PLAYER_ERROR;
  /**
   * A position discontinuity occurred. See {@link
   * Player.EventListener#onPositionDiscontinuity(int)}.
   */
  int EVENT_POSITION_DISCONTINUITY = Player.EVENT_POSITION_DISCONTINUITY;
  /** {@link Player#getPlaybackParameters()} changed. */
  int EVENT_PLAYBACK_PARAMETERS_CHANGED = Player.EVENT_PLAYBACK_PARAMETERS_CHANGED;
  /** A source started loading data. */
  int EVENT_LOAD_STARTED = 1000; // Intentional gap to leave space for new Player events
  /** A source started completed loading data. */
  int EVENT_LOAD_COMPLETED = 1001;
  /** A source canceled loading data. */
  int EVENT_LOAD_CANCELED = 1002;
  /** A source had a non-fatal error loading data. */
  int EVENT_LOAD_ERROR = 1003;
  /** The downstream format sent to renderers changed. */
  int EVENT_DOWNSTREAM_FORMAT_CHANGED = 1004;
  /** Data was removed from the end of the media buffer. */
  int EVENT_UPSTREAM_DISCARDED = 1005;
  /** The bandwidth estimate has been updated. */
  int EVENT_BANDWIDTH_ESTIMATE = 1006;
  /** Metadata associated with the current playback time was reported. */
  int EVENT_METADATA = 1007;
  /** An audio renderer was enabled. */
  int EVENT_AUDIO_ENABLED = 1008;
  /** An audio renderer created a decoder. */
  int EVENT_AUDIO_DECODER_INITIALIZED = 1009;
  /** The format consumed by an audio renderer changed. */
  int EVENT_AUDIO_INPUT_FORMAT_CHANGED = 1010;
  /** The audio position has increased for the first time since the last pause or position reset. */
  int EVENT_AUDIO_POSITION_ADVANCING = 1011;
  /** An audio underrun occurred. */
  int EVENT_AUDIO_UNDERRUN = 1012;
  /** An audio renderer released a decoder. */
  int EVENT_AUDIO_DECODER_RELEASED = 1013;
  /** An audio renderer was disabled. */
  int EVENT_AUDIO_DISABLED = 1014;
  /** An audio session id was set. */
  int EVENT_AUDIO_SESSION_ID = 1015;
  /** Audio attributes changed. */
  int EVENT_AUDIO_ATTRIBUTES_CHANGED = 1016;
  /** Skipping silences was enabled or disabled in the audio stream. */
  int EVENT_SKIP_SILENCE_ENABLED_CHANGED = 1017;
  /** The audio sink encountered a non-fatal error. */
  int EVENT_AUDIO_SINK_ERROR = 1018;
  /** The volume changed. */
  int EVENT_VOLUME_CHANGED = 1019;
  /** A video renderer was enabled. */
  int EVENT_VIDEO_ENABLED = 1020;
  /** A video renderer created a decoder. */
  int EVENT_VIDEO_DECODER_INITIALIZED = 1021;
  /** The format consumed by a video renderer changed. */
  int EVENT_VIDEO_INPUT_FORMAT_CHANGED = 1022;
  /** Video frames have been dropped. */
  int EVENT_DROPPED_VIDEO_FRAMES = 1023;
  /** A video renderer released a decoder. */
  int EVENT_VIDEO_DECODER_RELEASED = 1024;
  /** A video renderer was disabled. */
  int EVENT_VIDEO_DISABLED = 1025;
  /** Video frame processing offset data has been reported. */
  int EVENT_VIDEO_FRAME_PROCESSING_OFFSET = 1026;
  /**
   * The first frame has been rendered since setting the surface, since the renderer was reset or
   * since the stream changed.
   */
  int EVENT_RENDERED_FIRST_FRAME = 1027;
  /** The video size changed. */
  int EVENT_VIDEO_SIZE_CHANGED = 1028;
  /** The surface size changed. */
  int EVENT_SURFACE_SIZE_CHANGED = 1029;
  /** A DRM session has been acquired. */
  int EVENT_DRM_SESSION_ACQUIRED = 1030;
  /** DRM keys were loaded. */
  int EVENT_DRM_KEYS_LOADED = 1031;
  /** A non-fatal DRM session manager error occurred. */
  int EVENT_DRM_SESSION_MANAGER_ERROR = 1032;
  /** DRM keys were restored. */
  int EVENT_DRM_KEYS_RESTORED = 1033;
  /** DRM keys were removed. */
  int EVENT_DRM_KEYS_REMOVED = 1034;
  /** A DRM session has been released. */
  int EVENT_DRM_SESSION_RELEASED = 1035;
  /** The player was released. */
  int EVENT_PLAYER_RELEASED = 1036;

  /** Time information of an event. */
  final class EventTime {

    /**
     * Elapsed real-time as returned by {@code SystemClock.elapsedRealtime()} at the time of the
     * event, in milliseconds.
     */
    public final long realtimeMs;

    /** Most recent {@link Timeline} that contains the event position. */
    public final Timeline timeline;

    /**
     * Window index in the {@link #timeline} this event belongs to, or the prospective window index
     * if the timeline is not yet known and empty.
     */
    public final int windowIndex;

    /**
     * {@link MediaPeriodId Media period identifier} for the media period this event belongs to, or
     * {@code null} if the event is not associated with a specific media period.
     */
    @Nullable public final MediaPeriodId mediaPeriodId;

    /**
     * Position in the window or ad this event belongs to at the time of the event, in milliseconds.
     */
    public final long eventPlaybackPositionMs;

    /**
     * The current {@link Timeline} at the time of the event (equivalent to {@link
     * Player#getCurrentTimeline()}).
     */
    public final Timeline currentTimeline;

    /**
     * The current window index in {@link #currentTimeline} at the time of the event, or the
     * prospective window index if the timeline is not yet known and empty (equivalent to {@link
     * Player#getCurrentWindowIndex()}).
     */
    public final int currentWindowIndex;

    /**
     * {@link MediaPeriodId Media period identifier} for the currently playing media period at the
     * time of the event, or {@code null} if no current media period identifier is available.
     */
    @Nullable public final MediaPeriodId currentMediaPeriodId;

    /**
     * Position in the {@link #currentWindowIndex current timeline window} or the currently playing
     * ad at the time of the event, in milliseconds.
     */
    public final long currentPlaybackPositionMs;

    /**
     * Total buffered duration from {@link #currentPlaybackPositionMs} at the time of the event, in
     * milliseconds. This includes pre-buffered data for subsequent ads and windows.
     */
    public final long totalBufferedDurationMs;

    /**
     * @param realtimeMs Elapsed real-time as returned by {@code SystemClock.elapsedRealtime()} at
     *     the time of the event, in milliseconds.
     * @param timeline Most recent {@link Timeline} that contains the event position.
     * @param windowIndex Window index in the {@code timeline} this event belongs to, or the
     *     prospective window index if the timeline is not yet known and empty.
     * @param mediaPeriodId {@link MediaPeriodId Media period identifier} for the media period this
     *     event belongs to, or {@code null} if the event is not associated with a specific media
     *     period.
     * @param eventPlaybackPositionMs Position in the window or ad this event belongs to at the time
     *     of the event, in milliseconds.
     * @param currentTimeline The current {@link Timeline} at the time of the event (equivalent to
     *     {@link Player#getCurrentTimeline()}).
     * @param currentWindowIndex The current window index in {@code currentTimeline} at the time of
     *     the event, or the prospective window index if the timeline is not yet known and empty
     *     (equivalent to {@link Player#getCurrentWindowIndex()}).
     * @param currentMediaPeriodId {@link MediaPeriodId Media period identifier} for the currently
     *     playing media period at the time of the event, or {@code null} if no current media period
     *     identifier is available.
     * @param currentPlaybackPositionMs Position in the current timeline window or the currently
     *     playing ad at the time of the event, in milliseconds.
     * @param totalBufferedDurationMs Total buffered duration from {@code currentPlaybackPositionMs}
     *     at the time of the event, in milliseconds. This includes pre-buffered data for subsequent
     *     ads and windows.
     */
    public EventTime(
        long realtimeMs,
        Timeline timeline,
        int windowIndex,
        @Nullable MediaPeriodId mediaPeriodId,
        long eventPlaybackPositionMs,
        Timeline currentTimeline,
        int currentWindowIndex,
        @Nullable MediaPeriodId currentMediaPeriodId,
        long currentPlaybackPositionMs,
        long totalBufferedDurationMs) {
      this.realtimeMs = realtimeMs;
      this.timeline = timeline;
      this.windowIndex = windowIndex;
      this.mediaPeriodId = mediaPeriodId;
      this.eventPlaybackPositionMs = eventPlaybackPositionMs;
      this.currentTimeline = currentTimeline;
      this.currentWindowIndex = currentWindowIndex;
      this.currentMediaPeriodId = currentMediaPeriodId;
      this.currentPlaybackPositionMs = currentPlaybackPositionMs;
      this.totalBufferedDurationMs = totalBufferedDurationMs;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      EventTime eventTime = (EventTime) o;
      return realtimeMs == eventTime.realtimeMs
          && windowIndex == eventTime.windowIndex
          && eventPlaybackPositionMs == eventTime.eventPlaybackPositionMs
          && currentWindowIndex == eventTime.currentWindowIndex
          && currentPlaybackPositionMs == eventTime.currentPlaybackPositionMs
          && totalBufferedDurationMs == eventTime.totalBufferedDurationMs
          && Objects.equal(timeline, eventTime.timeline)
          && Objects.equal(mediaPeriodId, eventTime.mediaPeriodId)
          && Objects.equal(currentTimeline, eventTime.currentTimeline)
          && Objects.equal(currentMediaPeriodId, eventTime.currentMediaPeriodId);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(
          realtimeMs,
          timeline,
          windowIndex,
          mediaPeriodId,
          eventPlaybackPositionMs,
          currentTimeline,
          currentWindowIndex,
          currentMediaPeriodId,
          currentPlaybackPositionMs,
          totalBufferedDurationMs);
    }
  }

  /**
   * @deprecated Use {@link #onPlaybackStateChanged(EventTime, int)} and {@link
   *     #onPlayWhenReadyChanged(EventTime, boolean, int)} instead.
   */
  @Deprecated
  default void onPlayerStateChanged(
      EventTime eventTime, boolean playWhenReady, @Player.State int playbackState) {}

  /**
   * Called when the playback state changed.
   *
   * @param eventTime The event time.
   * @param state The new {@link Player.State playback state}.
   */
  default void onPlaybackStateChanged(EventTime eventTime, @Player.State int state) {}

  /**
   * Called when the value changed that indicates whether playback will proceed when ready.
   *
   * @param eventTime The event time.
   * @param playWhenReady Whether playback will proceed when ready.
   * @param reason The {@link Player.PlayWhenReadyChangeReason reason} of the change.
   */
  default void onPlayWhenReadyChanged(
      EventTime eventTime, boolean playWhenReady, @Player.PlayWhenReadyChangeReason int reason) {}

  /**
   * Called when playback suppression reason changed.
   *
   * @param eventTime The event time.
   * @param playbackSuppressionReason The new {@link PlaybackSuppressionReason}.
   */
  default void onPlaybackSuppressionReasonChanged(
      EventTime eventTime, @PlaybackSuppressionReason int playbackSuppressionReason) {}

  /**
   * Called when the player starts or stops playing.
   *
   * @param eventTime The event time.
   * @param isPlaying Whether the player is playing.
   */
  default void onIsPlayingChanged(EventTime eventTime, boolean isPlaying) {}

  /**
   * Called when the timeline changed.
   *
   * @param eventTime The event time.
   * @param reason The reason for the timeline change.
   */
  default void onTimelineChanged(EventTime eventTime, @TimelineChangeReason int reason) {}

  /**
   * Called when playback transitions to a different media item.
   *
   * @param eventTime The event time.
   * @param mediaItem The media item.
   * @param reason The reason for the media item transition.
   */
  default void onMediaItemTransition(
      EventTime eventTime,
      @Nullable MediaItem mediaItem,
      @Player.MediaItemTransitionReason int reason) {}

  /**
   * Called when a position discontinuity occurred.
   *
   * @param eventTime The event time.
   * @param reason The reason for the position discontinuity.
   */
  default void onPositionDiscontinuity(EventTime eventTime, @DiscontinuityReason int reason) {}

  /**
   * Called when a seek operation started.
   *
   * @param eventTime The event time.
   */
  default void onSeekStarted(EventTime eventTime) {}

  /**
   * @deprecated Seeks are processed without delay. Use {@link #onPositionDiscontinuity(EventTime,
   *     int)} with reason {@link Player#DISCONTINUITY_REASON_SEEK} instead.
   */
  @Deprecated
  default void onSeekProcessed(EventTime eventTime) {}

  /**
   * Called when the playback parameters changed.
   *
   * @param eventTime The event time.
   * @param playbackParameters The new playback parameters.
   */
  default void onPlaybackParametersChanged(
      EventTime eventTime, PlaybackParameters playbackParameters) {}

  /**
   * Called when the repeat mode changed.
   *
   * @param eventTime The event time.
   * @param repeatMode The new repeat mode.
   */
  default void onRepeatModeChanged(EventTime eventTime, @Player.RepeatMode int repeatMode) {}

  /**
   * Called when the shuffle mode changed.
   *
   * @param eventTime The event time.
   * @param shuffleModeEnabled Whether the shuffle mode is enabled.
   */
  default void onShuffleModeChanged(EventTime eventTime, boolean shuffleModeEnabled) {}

  /**
   * Called when the player starts or stops loading data from a source.
   *
   * @param eventTime The event time.
   * @param isLoading Whether the player is loading.
   */
  @SuppressWarnings("deprecation")
  default void onIsLoadingChanged(EventTime eventTime, boolean isLoading) {
    onLoadingChanged(eventTime, isLoading);
  }

  /** @deprecated Use {@link #onIsLoadingChanged(EventTime, boolean)} instead. */
  @Deprecated
  default void onLoadingChanged(EventTime eventTime, boolean isLoading) {}

  /**
   * Called when a fatal player error occurred.
   *
   * @param eventTime The event time.
   * @param error The error.
   */
  default void onPlayerError(EventTime eventTime, ExoPlaybackException error) {}

  /**
   * Called when the available or selected tracks for the renderers changed.
   *
   * @param eventTime The event time.
   * @param trackGroups The available tracks. May be empty.
   * @param trackSelections The track selections for each renderer. May contain null elements.
   */
  default void onTracksChanged(
      EventTime eventTime, TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {}

  /**
   * Called when the static metadata changes.
   *
   * <p>The provided {@code metadataList} is an immutable list of {@link Metadata} instances, where
   * the elements correspond to the current track selections (as returned by {@link
   * #onTracksChanged(EventTime, TrackGroupArray, TrackSelectionArray)}, or an empty list if there
   * are no track selections or the selected tracks contain no static metadata.
   *
   * <p>The metadata is considered static in the sense that it comes from the tracks' declared
   * Formats, rather than being timed (or dynamic) metadata, which is represented within a metadata
   * track.
   *
   * @param eventTime The event time.
   * @param metadataList The static metadata.
   */
  default void onStaticMetadataChanged(EventTime eventTime, List<Metadata> metadataList) {}

  /**
   * Called when a media source started loading data.
   *
   * @param eventTime The event time.
   * @param loadEventInfo The {@link LoadEventInfo} defining the load event.
   * @param mediaLoadData The {@link MediaLoadData} defining the data being loaded.
   */
  default void onLoadStarted(
      EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {}

  /**
   * Called when a media source completed loading data.
   *
   * @param eventTime The event time.
   * @param loadEventInfo The {@link LoadEventInfo} defining the load event.
   * @param mediaLoadData The {@link MediaLoadData} defining the data being loaded.
   */
  default void onLoadCompleted(
      EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {}

  /**
   * Called when a media source canceled loading data.
   *
   * @param eventTime The event time.
   * @param loadEventInfo The {@link LoadEventInfo} defining the load event.
   * @param mediaLoadData The {@link MediaLoadData} defining the data being loaded.
   */
  default void onLoadCanceled(
      EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {}

  /**
   * Called when a media source loading error occurred. These errors are just for informational
   * purposes and the player may recover.
   *
   * @param eventTime The event time.
   * @param loadEventInfo The {@link LoadEventInfo} defining the load event.
   * @param mediaLoadData The {@link MediaLoadData} defining the data being loaded.
   * @param error The load error.
   * @param wasCanceled Whether the load was canceled as a result of the error.
   */
  default void onLoadError(
      EventTime eventTime,
      LoadEventInfo loadEventInfo,
      MediaLoadData mediaLoadData,
      IOException error,
      boolean wasCanceled) {}

  /**
   * Called when the downstream format sent to the renderers changed.
   *
   * @param eventTime The event time.
   * @param mediaLoadData The {@link MediaLoadData} defining the newly selected media data.
   */
  default void onDownstreamFormatChanged(EventTime eventTime, MediaLoadData mediaLoadData) {}

  /**
   * Called when data is removed from the back of a media buffer, typically so that it can be
   * re-buffered in a different format.
   *
   * @param eventTime The event time.
   * @param mediaLoadData The {@link MediaLoadData} defining the media being discarded.
   */
  default void onUpstreamDiscarded(EventTime eventTime, MediaLoadData mediaLoadData) {}

  /**
   * Called when the bandwidth estimate for the current data source has been updated.
   *
   * @param eventTime The event time.
   * @param totalLoadTimeMs The total time spend loading this update is based on, in milliseconds.
   * @param totalBytesLoaded The total bytes loaded this update is based on.
   * @param bitrateEstimate The bandwidth estimate, in bits per second.
   */
  default void onBandwidthEstimate(
      EventTime eventTime, int totalLoadTimeMs, long totalBytesLoaded, long bitrateEstimate) {}

  /**
   * Called when there is {@link Metadata} associated with the current playback time.
   *
   * @param eventTime The event time.
   * @param metadata The metadata.
   */
  default void onMetadata(EventTime eventTime, Metadata metadata) {}

  /** @deprecated Use {@link #onAudioEnabled} and {@link #onVideoEnabled} instead. */
  @Deprecated
  default void onDecoderEnabled(
      EventTime eventTime, int trackType, DecoderCounters decoderCounters) {}

  /**
   * @deprecated Use {@link #onAudioDecoderInitialized} and {@link #onVideoDecoderInitialized}
   *     instead.
   */
  @Deprecated
  default void onDecoderInitialized(
      EventTime eventTime, int trackType, String decoderName, long initializationDurationMs) {}

  /**
   * @deprecated Use {@link #onAudioInputFormatChanged(EventTime, Format, DecoderReuseEvaluation)}
   *     and {@link #onVideoInputFormatChanged(EventTime, Format, DecoderReuseEvaluation)}. instead.
   */
  @Deprecated
  default void onDecoderInputFormatChanged(EventTime eventTime, int trackType, Format format) {}

  /** @deprecated Use {@link #onAudioDisabled} and {@link #onVideoDisabled} instead. */
  @Deprecated
  default void onDecoderDisabled(
      EventTime eventTime, int trackType, DecoderCounters decoderCounters) {}

  /**
   * Called when an audio renderer is enabled.
   *
   * @param eventTime The event time.
   * @param counters {@link DecoderCounters} that will be updated by the renderer for as long as it
   *     remains enabled.
   */
  default void onAudioEnabled(EventTime eventTime, DecoderCounters counters) {}

  /**
   * Called when an audio renderer creates a decoder.
   *
   * @param eventTime The event time.
   * @param decoderName The decoder that was created.
   * @param initializationDurationMs The time taken to initialize the decoder in milliseconds.
   */
  default void onAudioDecoderInitialized(
      EventTime eventTime, String decoderName, long initializationDurationMs) {}

  /**
   * @deprecated Use {@link #onAudioInputFormatChanged(EventTime, Format, DecoderReuseEvaluation)}.
   */
  @Deprecated
  default void onAudioInputFormatChanged(EventTime eventTime, Format format) {}

  /**
   * Called when the format of the media being consumed by an audio renderer changes.
   *
   * @param eventTime The event time.
   * @param format The new format.
   * @param decoderReuseEvaluation The result of the evaluation to determine whether an existing
   *     decoder instance can be reused for the new format, or {@code null} if the renderer did not
   *     have a decoder.
   */
  @SuppressWarnings("deprecation")
  default void onAudioInputFormatChanged(
      EventTime eventTime, Format format, @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
    onAudioInputFormatChanged(eventTime, format);
  }

  /**
   * Called when the audio position has increased for the first time since the last pause or
   * position reset.
   *
   * @param eventTime The event time.
   * @param playoutStartSystemTimeMs The approximate derived {@link System#currentTimeMillis()} at
   *     which playout started.
   */
  default void onAudioPositionAdvancing(EventTime eventTime, long playoutStartSystemTimeMs) {}

  /**
   * Called when an audio underrun occurs.
   *
   * @param eventTime The event time.
   * @param bufferSize The size of the audio output buffer, in bytes.
   * @param bufferSizeMs The size of the audio output buffer, in milliseconds, if it contains PCM
   *     encoded audio. {@link C#TIME_UNSET} if the output buffer contains non-PCM encoded audio.
   * @param elapsedSinceLastFeedMs The time since audio was last written to the output buffer.
   */
  default void onAudioUnderrun(
      EventTime eventTime, int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {}

  /**
   * Called when an audio renderer releases a decoder.
   *
   * @param eventTime The event time.
   * @param decoderName The decoder that was released.
   */
  default void onAudioDecoderReleased(EventTime eventTime, String decoderName) {}

  /**
   * Called when an audio renderer is disabled.
   *
   * @param eventTime The event time.
   * @param counters {@link DecoderCounters} that were updated by the renderer.
   */
  default void onAudioDisabled(EventTime eventTime, DecoderCounters counters) {}

  /**
   * Called when the audio session ID changes.
   *
   * @param eventTime The event time.
   * @param audioSessionId The audio session ID.
   */
  default void onAudioSessionIdChanged(EventTime eventTime, int audioSessionId) {}

  /**
   * Called when the audio attributes change.
   *
   * @param eventTime The event time.
   * @param audioAttributes The audio attributes.
   */
  default void onAudioAttributesChanged(EventTime eventTime, AudioAttributes audioAttributes) {}

  /**
   * Called when skipping silences is enabled or disabled in the audio stream.
   *
   * @param eventTime The event time.
   * @param skipSilenceEnabled Whether skipping silences in the audio stream is enabled.
   */
  default void onSkipSilenceEnabledChanged(EventTime eventTime, boolean skipSilenceEnabled) {}

  /**
   * Called when {@link AudioSink} has encountered an error. These errors are just for informational
   * purposes and the player may recover.
   *
   * @param eventTime The event time.
   * @param audioSinkError Either a {@link AudioSink.InitializationException} or a {@link
   *     AudioSink.WriteException} describing the error.
   */
  default void onAudioSinkError(EventTime eventTime, Exception audioSinkError) {}

  /**
   * Called when the volume changes.
   *
   * @param eventTime The event time.
   * @param volume The new volume, with 0 being silence and 1 being unity gain.
   */
  default void onVolumeChanged(EventTime eventTime, float volume) {}

  /**
   * Called when a video renderer is enabled.
   *
   * @param eventTime The event time.
   * @param counters {@link DecoderCounters} that will be updated by the renderer for as long as it
   *     remains enabled.
   */
  default void onVideoEnabled(EventTime eventTime, DecoderCounters counters) {}

  /**
   * Called when a video renderer creates a decoder.
   *
   * @param eventTime The event time.
   * @param decoderName The decoder that was created.
   * @param initializationDurationMs The time taken to initialize the decoder in milliseconds.
   */
  default void onVideoDecoderInitialized(
      EventTime eventTime, String decoderName, long initializationDurationMs) {}

  /**
   * @deprecated Use {@link #onVideoInputFormatChanged(EventTime, Format, DecoderReuseEvaluation)}.
   */
  @Deprecated
  default void onVideoInputFormatChanged(EventTime eventTime, Format format) {}

  /**
   * Called when the format of the media being consumed by a video renderer changes.
   *
   * @param eventTime The event time.
   * @param format The new format.
   * @param decoderReuseEvaluation The result of the evaluation to determine whether an existing
   *     decoder instance can be reused for the new format, or {@code null} if the renderer did not
   *     have a decoder.
   */
  @SuppressWarnings("deprecation")
  default void onVideoInputFormatChanged(
      EventTime eventTime, Format format, @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
    onVideoInputFormatChanged(eventTime, format);
  }

  /**
   * Called after video frames have been dropped.
   *
   * @param eventTime The event time.
   * @param droppedFrames The number of dropped frames since the last call to this method.
   * @param elapsedMs The duration in milliseconds over which the frames were dropped. This duration
   *     is timed from when the renderer was started or from when dropped frames were last reported
   *     (whichever was more recent), and not from when the first of the reported drops occurred.
   */
  default void onDroppedVideoFrames(EventTime eventTime, int droppedFrames, long elapsedMs) {}

  /**
   * Called when a video renderer releases a decoder.
   *
   * @param eventTime The event time.
   * @param decoderName The decoder that was released.
   */
  default void onVideoDecoderReleased(EventTime eventTime, String decoderName) {}

  /**
   * Called when a video renderer is disabled.
   *
   * @param eventTime The event time.
   * @param counters {@link DecoderCounters} that were updated by the renderer.
   */
  default void onVideoDisabled(EventTime eventTime, DecoderCounters counters) {}

  /**
   * Called when there is an update to the video frame processing offset reported by a video
   * renderer.
   *
   * <p>The processing offset for a video frame is the difference between the time at which the
   * frame became available to render, and the time at which it was scheduled to be rendered. A
   * positive value indicates the frame became available early enough, whereas a negative value
   * indicates that the frame wasn't available until after the time at which it should have been
   * rendered.
   *
   * @param eventTime The event time.
   * @param totalProcessingOffsetUs The sum of the video frame processing offsets for frames
   *     rendered since the last call to this method.
   * @param frameCount The number to samples included in {@code totalProcessingOffsetUs}.
   */
  default void onVideoFrameProcessingOffset(
      EventTime eventTime, long totalProcessingOffsetUs, int frameCount) {}

  /**
   * Called when a frame is rendered for the first time since setting the surface, or since the
   * renderer was reset, or since the stream being rendered was changed.
   *
   * @param eventTime The event time.
   * @param surface The {@link Surface} to which a frame has been rendered, or {@code null} if the
   *     renderer renders to something that isn't a {@link Surface}.
   */
  default void onRenderedFirstFrame(EventTime eventTime, @Nullable Surface surface) {}

  /**
   * Called before a frame is rendered for the first time since setting the surface, and each time
   * there's a change in the size or pixel aspect ratio of the video being rendered.
   *
   * @param eventTime The event time.
   * @param width The width of the video.
   * @param height The height of the video.
   * @param unappliedRotationDegrees For videos that require a rotation, this is the clockwise
   *     rotation in degrees that the application should apply for the video for it to be rendered
   *     in the correct orientation. This value will always be zero on API levels 21 and above,
   *     since the renderer will apply all necessary rotations internally.
   * @param pixelWidthHeightRatio The width to height ratio of each pixel.
   */
  default void onVideoSizeChanged(
      EventTime eventTime,
      int width,
      int height,
      int unappliedRotationDegrees,
      float pixelWidthHeightRatio) {}

  /**
   * Called when the output surface size changed.
   *
   * @param eventTime The event time.
   * @param width The surface width in pixels. May be {@link C#LENGTH_UNSET} if unknown, or 0 if the
   *     video is not rendered onto a surface.
   * @param height The surface height in pixels. May be {@link C#LENGTH_UNSET} if unknown, or 0 if
   *     the video is not rendered onto a surface.
   */
  default void onSurfaceSizeChanged(EventTime eventTime, int width, int height) {}

  /**
   * Called each time a drm session is acquired.
   *
   * @param eventTime The event time.
   */
  default void onDrmSessionAcquired(EventTime eventTime) {}

  /**
   * Called each time drm keys are loaded.
   *
   * @param eventTime The event time.
   */
  default void onDrmKeysLoaded(EventTime eventTime) {}

  /**
   * Called when a drm error occurs. These errors are just for informational purposes and the player
   * may recover.
   *
   * @param eventTime The event time.
   * @param error The error.
   */
  default void onDrmSessionManagerError(EventTime eventTime, Exception error) {}

  /**
   * Called each time offline drm keys are restored.
   *
   * @param eventTime The event time.
   */
  default void onDrmKeysRestored(EventTime eventTime) {}

  /**
   * Called each time offline drm keys are removed.
   *
   * @param eventTime The event time.
   */
  default void onDrmKeysRemoved(EventTime eventTime) {}

  /**
   * Called each time a drm session is released.
   *
   * @param eventTime The event time.
   */
  default void onDrmSessionReleased(EventTime eventTime) {}

  /**
   * Called when the {@link Player} is released.
   *
   * @param eventTime The event time.
   */
  default void onPlayerReleased(EventTime eventTime) {}

  /**
   * Called after one or more events occurred.
   *
   * <p>State changes and events that happen within one {@link Looper} message queue iteration are
   * reported together and only after all individual callbacks were triggered.
   *
   * <p>Listeners should prefer this method over individual callbacks in the following cases:
   *
   * <ul>
   *   <li>They intend to trigger the same logic for multiple events (e.g. when updating a UI for
   *       both {@link #onPlaybackStateChanged(EventTime, int)} and {@link
   *       #onPlayWhenReadyChanged(EventTime, boolean, int)}).
   *   <li>They need access to the {@link Player} object to trigger further events (e.g. to call
   *       {@link Player#seekTo(long)} after a {@link
   *       AnalyticsListener#onMediaItemTransition(EventTime, MediaItem, int)}).
   *   <li>They intend to use multiple state values together or in combination with {@link Player}
   *       getter methods. For example using {@link Player#getCurrentWindowIndex()} with the {@code
   *       timeline} provided in {@link #onTimelineChanged(EventTime, int)} is only safe from within
   *       this method.
   *   <li>They are interested in events that logically happened together (e.g {@link
   *       #onPlaybackStateChanged(EventTime, int)} to {@link Player#STATE_BUFFERING} because of
   *       {@link #onMediaItemTransition(EventTime, MediaItem, int)}).
   * </ul>
   *
   * @param player The {@link Player}.
   * @param events The {@link Events} that occurred in this iteration.
   */
  default void onEvents(Player player, Events events) {}
}
