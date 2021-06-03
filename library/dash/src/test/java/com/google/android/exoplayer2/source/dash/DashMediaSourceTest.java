/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.google.android.exoplayer2.source.dash;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.fail;
import static org.robolectric.annotation.LooperMode.Mode.PAUSED;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.Timeline.Window;
import com.google.android.exoplayer2.offline.StreamKey;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MediaSource.MediaSourceCaller;
import com.google.android.exoplayer2.testutil.TestUtil;
import com.google.android.exoplayer2.upstream.ByteArrayDataSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.FileDataSource;
import com.google.android.exoplayer2.upstream.ParsingLoadable;
import com.google.android.exoplayer2.util.Util;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

/** Unit test for {@link DashMediaSource}. */
@RunWith(AndroidJUnit4.class)
@LooperMode(PAUSED)
public final class DashMediaSourceTest {

  private static final String SAMPLE_MPD_LIVE_WITHOUT_LIVE_CONFIGURATION =
      "media/mpd/sample_mpd_live_without_live_configuration";
  private static final String
      SAMPLE_MPD_LIVE_WITH_SUGGESTED_PRESENTATION_DELAY_2S_MIN_BUFFER_TIME_500MS =
          "media/mpd/sample_mpd_live_with_suggested_presentation_delay_2s_min_buffer_time_500ms";
  private static final String SAMPLE_MPD_LIVE_WITH_COMPLETE_SERVICE_DESCRIPTION =
      "media/mpd/sample_mpd_live_with_complete_service_description";
  private static final String SAMPLE_MPD_LIVE_WITH_OFFSET_INSIDE_WINDOW =
      "media/mpd/sample_mpd_live_with_offset_inside_window";
  private static final String SAMPLE_MPD_LIVE_WITH_OFFSET_TOO_SHORT =
      "media/mpd/sample_mpd_live_with_offset_too_short";
  private static final String SAMPLE_MPD_LIVE_WITH_OFFSET_TOO_LONG =
      "media/mpd/sample_mpd_live_with_offset_too_long";

  @Test
  public void iso8601ParserParse() throws IOException {
    DashMediaSource.Iso8601Parser parser = new DashMediaSource.Iso8601Parser();
    // UTC.
    assertParseStringToLong(1512381697000L, parser, "2017-12-04T10:01:37Z");
    assertParseStringToLong(1512381697000L, parser, "2017-12-04T10:01:37+00:00");
    assertParseStringToLong(1512381697000L, parser, "2017-12-04T10:01:37+0000");
    assertParseStringToLong(1512381697000L, parser, "2017-12-04T10:01:37+00");
    // Positive timezone offsets.
    assertParseStringToLong(1512381697000L - 4980000L, parser, "2017-12-04T10:01:37+01:23");
    assertParseStringToLong(1512381697000L - 4980000L, parser, "2017-12-04T10:01:37+0123");
    assertParseStringToLong(1512381697000L - 3600000L, parser, "2017-12-04T10:01:37+01");
    // Negative timezone offsets with minus character.
    assertParseStringToLong(1512381697000L + 4980000L, parser, "2017-12-04T10:01:37-01:23");
    assertParseStringToLong(1512381697000L + 4980000L, parser, "2017-12-04T10:01:37-0123");
    assertParseStringToLong(1512381697000L + 3600000L, parser, "2017-12-04T10:01:37-01:00");
    assertParseStringToLong(1512381697000L + 3600000L, parser, "2017-12-04T10:01:37-0100");
    assertParseStringToLong(1512381697000L + 3600000L, parser, "2017-12-04T10:01:37-01");
    // Negative timezone offsets with hyphen character.
    assertParseStringToLong(1512381697000L + 4980000L, parser, "2017-12-04T10:01:37−01:23");
    assertParseStringToLong(1512381697000L + 4980000L, parser, "2017-12-04T10:01:37−0123");
    assertParseStringToLong(1512381697000L + 3600000L, parser, "2017-12-04T10:01:37−01:00");
    assertParseStringToLong(1512381697000L + 3600000L, parser, "2017-12-04T10:01:37−0100");
    assertParseStringToLong(1512381697000L + 3600000L, parser, "2017-12-04T10:01:37−01");
  }

  @Test
  public void iso8601ParserParseMissingTimezone() throws IOException {
    DashMediaSource.Iso8601Parser parser = new DashMediaSource.Iso8601Parser();
    try {
      assertParseStringToLong(0, parser, "2017-12-04T10:01:37");
      fail();
    } catch (ParserException e) {
      // Expected.
    }
  }

  // Tests backwards compatibility
  @SuppressWarnings("deprecation")
  @Test
  public void factorySetTag_nullMediaItemTag_setsMediaItemTag() {
    Object tag = new Object();
    MediaItem mediaItem = MediaItem.fromUri("http://www.google.com");
    DashMediaSource.Factory factory =
        new DashMediaSource.Factory(new FileDataSource.Factory()).setTag(tag);

    MediaItem dashMediaItem = factory.createMediaSource(mediaItem).getMediaItem();

    assertThat(dashMediaItem.playbackProperties).isNotNull();
    assertThat(dashMediaItem.playbackProperties.uri).isEqualTo(mediaItem.playbackProperties.uri);
    assertThat(dashMediaItem.playbackProperties.tag).isEqualTo(tag);
  }

  // Tests backwards compatibility
  @SuppressWarnings("deprecation")
  @Test
  public void factorySetTag_nonNullMediaItemTag_doesNotOverrideMediaItemTag() {
    Object factoryTag = new Object();
    Object mediaItemTag = new Object();
    MediaItem mediaItem =
        new MediaItem.Builder().setUri("http://www.google.com").setTag(mediaItemTag).build();
    DashMediaSource.Factory factory =
        new DashMediaSource.Factory(new FileDataSource.Factory()).setTag(factoryTag);

    MediaItem dashMediaItem = factory.createMediaSource(mediaItem).getMediaItem();

    assertThat(dashMediaItem.playbackProperties).isNotNull();
    assertThat(dashMediaItem.playbackProperties.uri).isEqualTo(mediaItem.playbackProperties.uri);
    assertThat(dashMediaItem.playbackProperties.tag).isEqualTo(mediaItemTag);
  }

  // Tests backwards compatibility
  @SuppressWarnings("deprecation")
  @Test
  public void factorySetTag_setsDeprecatedMediaSourceTag() {
    Object tag = new Object();
    MediaItem mediaItem = MediaItem.fromUri("http://www.google.com");
    DashMediaSource.Factory factory =
        new DashMediaSource.Factory(new FileDataSource.Factory()).setTag(tag);

    @Nullable Object mediaSourceTag = factory.createMediaSource(mediaItem).getTag();

    assertThat(mediaSourceTag).isEqualTo(tag);
  }

  // Tests backwards compatibility
  @SuppressWarnings("deprecation")
  @Test
  public void factoryCreateMediaSource_setsDeprecatedMediaSourceTag() {
    Object tag = new Object();
    MediaItem mediaItem =
        new MediaItem.Builder().setUri("http://www.google.com").setTag(tag).build();
    DashMediaSource.Factory factory =
        new DashMediaSource.Factory(new FileDataSource.Factory()).setTag(new Object());

    @Nullable Object mediaSourceTag = factory.createMediaSource(mediaItem).getTag();

    assertThat(mediaSourceTag).isEqualTo(tag);
  }

  // Tests backwards compatibility
  @SuppressWarnings("deprecation")
  @Test
  public void factorySetStreamKeys_emptyMediaItemStreamKeys_setsMediaItemStreamKeys() {
    MediaItem mediaItem = MediaItem.fromUri("http://www.google.com");
    StreamKey streamKey = new StreamKey(/* groupIndex= */ 0, /* trackIndex= */ 1);
    DashMediaSource.Factory factory =
        new DashMediaSource.Factory(new FileDataSource.Factory())
            .setStreamKeys(ImmutableList.of(streamKey));

    MediaItem dashMediaItem = factory.createMediaSource(mediaItem).getMediaItem();

    assertThat(dashMediaItem.playbackProperties).isNotNull();
    assertThat(dashMediaItem.playbackProperties.uri).isEqualTo(mediaItem.playbackProperties.uri);
    assertThat(dashMediaItem.playbackProperties.streamKeys).containsExactly(streamKey);
  }

  // Tests backwards compatibility
  @SuppressWarnings("deprecation")
  @Test
  public void factorySetStreamKeys_withMediaItemStreamKeys_doesNotOverrideMediaItemStreamKeys() {
    StreamKey mediaItemStreamKey = new StreamKey(/* groupIndex= */ 0, /* trackIndex= */ 1);
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("http://www.google.com")
            .setStreamKeys(ImmutableList.of(mediaItemStreamKey))
            .build();
    DashMediaSource.Factory factory =
        new DashMediaSource.Factory(new FileDataSource.Factory())
            .setStreamKeys(
                ImmutableList.of(new StreamKey(/* groupIndex= */ 1, /* trackIndex= */ 0)));

    MediaItem dashMediaItem = factory.createMediaSource(mediaItem).getMediaItem();

    assertThat(dashMediaItem.playbackProperties).isNotNull();
    assertThat(dashMediaItem.playbackProperties.uri).isEqualTo(mediaItem.playbackProperties.uri);
    assertThat(dashMediaItem.playbackProperties.streamKeys).containsExactly(mediaItemStreamKey);
  }

  @Test
  public void replaceManifestUri_doesNotChangeMediaItem() {
    DashMediaSource.Factory factory = new DashMediaSource.Factory(new FileDataSource.Factory());
    MediaItem mediaItem = MediaItem.fromUri("http://www.google.com");
    DashMediaSource mediaSource = factory.createMediaSource(mediaItem);

    mediaSource.replaceManifestUri(Uri.EMPTY);

    assertThat(mediaSource.getMediaItem()).isEqualTo(mediaItem);
  }

  @Test
  public void factorySetFallbackTargetLiveOffsetMs_withMediaLiveTargetOffsetMs_usesMediaOffset() {
    MediaItem mediaItem =
        new MediaItem.Builder().setUri(Uri.EMPTY).setLiveTargetOffsetMs(2L).build();
    DashMediaSource.Factory factory =
        new DashMediaSource.Factory(new FileDataSource.Factory())
            .setFallbackTargetLiveOffsetMs(1234L);

    MediaItem dashMediaItem = factory.createMediaSource(mediaItem).getMediaItem();

    assertThat(dashMediaItem.liveConfiguration.targetOffsetMs).isEqualTo(2L);
  }

  @Test
  public void factorySetLivePresentationDelayMs_withMediaLiveTargetOffset_usesMediaOffset() {
    MediaItem mediaItem =
        new MediaItem.Builder().setUri(Uri.EMPTY).setLiveTargetOffsetMs(2L).build();
    DashMediaSource.Factory factory =
        new DashMediaSource.Factory(new FileDataSource.Factory())
            .setLivePresentationDelayMs(1234L, /* overridesManifest= */ true);

    MediaItem dashMediaItem = factory.createMediaSource(mediaItem).getMediaItem();

    assertThat(dashMediaItem.liveConfiguration.targetOffsetMs).isEqualTo(2L);
  }

  @Test
  public void factorySetLivePresentationDelayMs_overridingManifest_mixedIntoMediaItem() {
    MediaItem mediaItem = new MediaItem.Builder().setUri(Uri.EMPTY).build();
    DashMediaSource.Factory factory =
        new DashMediaSource.Factory(new FileDataSource.Factory())
            .setLivePresentationDelayMs(2000L, /* overridesManifest= */ true);

    MediaItem dashMediaItem = factory.createMediaSource(mediaItem).getMediaItem();

    assertThat(dashMediaItem.liveConfiguration.targetOffsetMs).isEqualTo(2000L);
  }

  @Test
  public void factorySetLivePresentationDelayMs_notOverridingManifest_unsetInMediaItem() {
    MediaItem mediaItem = new MediaItem.Builder().setUri(Uri.EMPTY).build();
    DashMediaSource.Factory factory =
        new DashMediaSource.Factory(new FileDataSource.Factory())
            .setLivePresentationDelayMs(2000L, /* overridesManifest= */ false);

    MediaItem dashMediaItem = factory.createMediaSource(mediaItem).getMediaItem();

    assertThat(dashMediaItem.liveConfiguration.targetOffsetMs).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void factorySetFallbackTargetLiveOffsetMs_doesNotChangeMediaItem() {
    DashMediaSource.Factory factory =
        new DashMediaSource.Factory(new FileDataSource.Factory())
            .setFallbackTargetLiveOffsetMs(2000L);

    MediaItem dashMediaItem =
        factory.createMediaSource(MediaItem.fromUri(Uri.EMPTY)).getMediaItem();

    assertThat(dashMediaItem.liveConfiguration.targetOffsetMs).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void prepare_withoutLiveConfiguration_withoutMediaItemLiveProperties_usesDefaultFallback()
      throws InterruptedException {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITHOUT_LIVE_CONFIGURATION))
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY));

    MediaItem.LiveConfiguration liveConfiguration =
        prepareAndWaitForTimelineRefresh(mediaSource).liveConfiguration;

    assertThat(liveConfiguration.targetOffsetMs)
        .isEqualTo(DashMediaSource.DEFAULT_FALLBACK_TARGET_LIVE_OFFSET_MS);
    assertThat(liveConfiguration.minOffsetMs).isEqualTo(0L);
    assertThat(liveConfiguration.maxOffsetMs).isEqualTo(58_000L);
    assertThat(liveConfiguration.minPlaybackSpeed).isEqualTo(C.RATE_UNSET);
    assertThat(liveConfiguration.maxPlaybackSpeed).isEqualTo(C.RATE_UNSET);
  }

  @Test
  public void prepare_withoutLiveConfiguration_withoutMediaItemLiveProperties_usesFallback()
      throws InterruptedException {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITHOUT_LIVE_CONFIGURATION))
            .setFallbackTargetLiveOffsetMs(1234L)
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY));

    MediaItem.LiveConfiguration liveConfiguration =
        prepareAndWaitForTimelineRefresh(mediaSource).liveConfiguration;

    assertThat(liveConfiguration.targetOffsetMs).isEqualTo(1234L);
    assertThat(liveConfiguration.minOffsetMs).isEqualTo(0L);
    assertThat(liveConfiguration.maxOffsetMs).isEqualTo(58_000L);
    assertThat(liveConfiguration.minPlaybackSpeed).isEqualTo(C.RATE_UNSET);
    assertThat(liveConfiguration.maxPlaybackSpeed).isEqualTo(C.RATE_UNSET);
  }

  @Test
  public void prepare_withoutLiveConfiguration_withMediaItemLiveProperties_usesMediaItem()
      throws InterruptedException {
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.EMPTY)
            .setLiveTargetOffsetMs(876L)
            .setLiveMinPlaybackSpeed(23f)
            .setLiveMaxPlaybackSpeed(42f)
            .setLiveMinOffsetMs(500L)
            .setLiveMaxOffsetMs(20_000L)
            .build();
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITHOUT_LIVE_CONFIGURATION))
            .setFallbackTargetLiveOffsetMs(1234L)
            .createMediaSource(mediaItem);

    MediaItem.LiveConfiguration liveConfiguration =
        prepareAndWaitForTimelineRefresh(mediaSource).liveConfiguration;

    assertThat(liveConfiguration).isEqualTo(mediaItem.liveConfiguration);
  }

  @Test
  public void prepare_withSuggestedPresentationDelayAndMinBufferTime_usesManifestValue()
      throws InterruptedException {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () ->
                    createSampleMpdDataSource(
                        SAMPLE_MPD_LIVE_WITH_SUGGESTED_PRESENTATION_DELAY_2S_MIN_BUFFER_TIME_500MS))
            .setFallbackTargetLiveOffsetMs(1234L)
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY));

    MediaItem.LiveConfiguration liveConfiguration =
        prepareAndWaitForTimelineRefresh(mediaSource).liveConfiguration;

    assertThat(liveConfiguration.targetOffsetMs).isEqualTo(2_000L);
    assertThat(liveConfiguration.minOffsetMs).isEqualTo(500L);
    assertThat(liveConfiguration.maxOffsetMs).isEqualTo(58_000L);
    assertThat(liveConfiguration.minPlaybackSpeed).isEqualTo(C.RATE_UNSET);
    assertThat(liveConfiguration.maxPlaybackSpeed).isEqualTo(C.RATE_UNSET);
  }

  @Test
  public void
      prepare_withSuggestedPresentationDelayAndMinBufferTime_withMediaItemLiveProperties_usesMediaItem()
          throws InterruptedException {
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.EMPTY)
            .setLiveTargetOffsetMs(876L)
            .setLiveMinPlaybackSpeed(23f)
            .setLiveMaxPlaybackSpeed(42f)
            .setLiveMinOffsetMs(200L)
            .setLiveMaxOffsetMs(999L)
            .build();
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () ->
                    createSampleMpdDataSource(
                        SAMPLE_MPD_LIVE_WITH_SUGGESTED_PRESENTATION_DELAY_2S_MIN_BUFFER_TIME_500MS))
            .setFallbackTargetLiveOffsetMs(1234L)
            .createMediaSource(mediaItem);

    MediaItem.LiveConfiguration liveConfiguration =
        prepareAndWaitForTimelineRefresh(mediaSource).liveConfiguration;

    assertThat(liveConfiguration.targetOffsetMs).isEqualTo(876L);
    assertThat(liveConfiguration.minOffsetMs).isEqualTo(200L);
    assertThat(liveConfiguration.maxOffsetMs).isEqualTo(999L);
    assertThat(liveConfiguration.minPlaybackSpeed).isEqualTo(23f);
    assertThat(liveConfiguration.maxPlaybackSpeed).isEqualTo(42f);
  }

  @Test
  public void prepare_withCompleteServiceDescription_usesManifestValue()
      throws InterruptedException {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITH_COMPLETE_SERVICE_DESCRIPTION))
            .setFallbackTargetLiveOffsetMs(1234L)
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY));

    MediaItem.LiveConfiguration liveConfiguration =
        prepareAndWaitForTimelineRefresh(mediaSource).liveConfiguration;

    assertThat(liveConfiguration.targetOffsetMs).isEqualTo(4_000L);
    assertThat(liveConfiguration.minOffsetMs).isEqualTo(2_000L);
    assertThat(liveConfiguration.maxOffsetMs).isEqualTo(6_000L);
    assertThat(liveConfiguration.minPlaybackSpeed).isEqualTo(0.96f);
    assertThat(liveConfiguration.maxPlaybackSpeed).isEqualTo(1.04f);
  }

  @Test
  public void prepare_withCompleteServiceDescription_withMediaItemLiveProperties_usesMediaItem()
      throws InterruptedException {
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.EMPTY)
            .setLiveTargetOffsetMs(876L)
            .setLiveMinPlaybackSpeed(23f)
            .setLiveMaxPlaybackSpeed(42f)
            .setLiveMinOffsetMs(100L)
            .setLiveMaxOffsetMs(999L)
            .build();
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITH_COMPLETE_SERVICE_DESCRIPTION))
            .setFallbackTargetLiveOffsetMs(1234L)
            .createMediaSource(mediaItem);

    MediaItem.LiveConfiguration liveConfiguration =
        prepareAndWaitForTimelineRefresh(mediaSource).liveConfiguration;

    assertThat(liveConfiguration.targetOffsetMs).isEqualTo(876L);
    assertThat(liveConfiguration.minOffsetMs).isEqualTo(100L);
    assertThat(liveConfiguration.maxOffsetMs).isEqualTo(999L);
    assertThat(liveConfiguration.minPlaybackSpeed).isEqualTo(23f);
    assertThat(liveConfiguration.maxPlaybackSpeed).isEqualTo(42f);
  }

  @Test
  public void prepare_targetLiveOffsetInWindow_manifestTargetOffsetAndAlignedWindowStartPosition()
      throws InterruptedException {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITH_OFFSET_INSIDE_WINDOW))
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY));

    Window window = prepareAndWaitForTimelineRefresh(mediaSource);

    // Expect the target live offset as defined in the manifest.
    assertThat(window.liveConfiguration.targetOffsetMs).isEqualTo(3000);
    // Expect the default position at the first segment start before the live edge.
    assertThat(window.getDefaultPositionMs()).isEqualTo(2_000);
  }

  @Test
  public void prepare_targetLiveOffsetTooLong_correctedTargetOffsetAndAlignedWindowStartPosition()
      throws InterruptedException {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITH_OFFSET_TOO_LONG))
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY));

    Window window = prepareAndWaitForTimelineRefresh(mediaSource);

    // Expect the default position at the first segment start below the minimum live start position.
    assertThat(window.getDefaultPositionMs()).isEqualTo(4_000);
    // Expect the target live offset reaching from now time to the minimum live start position.
    assertThat(window.liveConfiguration.targetOffsetMs).isEqualTo(9000);
  }

  @Test
  public void prepare_targetLiveOffsetTooShort_correctedTargetOffsetAndAlignedWindowStartPosition()
      throws InterruptedException {
    // Load manifest with now time far behind the start of the window.
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITH_OFFSET_TOO_SHORT))
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY));

    Window window = prepareAndWaitForTimelineRefresh(mediaSource);

    // Expect the default position at the start of the last segment.
    assertThat(window.getDefaultPositionMs()).isEqualTo(12_000);
    // Expect the target live offset reaching from now time to the end of the window.
    assertThat(window.liveConfiguration.targetOffsetMs).isEqualTo(60_000 - 16_000);
  }

  private static Window prepareAndWaitForTimelineRefresh(MediaSource mediaSource)
      throws InterruptedException {
    AtomicReference<Window> windowReference = new AtomicReference<>();
    CountDownLatch countDownLatch = new CountDownLatch(/* count= */ 1);
    MediaSourceCaller caller =
        (MediaSource source, Timeline timeline) -> {
          if (windowReference.get() == null) {
            windowReference.set(timeline.getWindow(0, new Timeline.Window()));
            countDownLatch.countDown();
          }
        };
    mediaSource.prepareSource(caller, /* mediaTransferListener= */ null);
    while (!countDownLatch.await(/* timeout= */ 10, MILLISECONDS)) {
      ShadowLooper.idleMainLooper();
    }
    return windowReference.get();
  }

  private static DataSource createSampleMpdDataSource(String fileName) {
    byte[] manifestData = new byte[0];
    try {
      manifestData = TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), fileName);
    } catch (IOException e) {
      fail(e.getMessage());
    }
    return new ByteArrayDataSource(manifestData);
  }

  private static void assertParseStringToLong(
      long expected, ParsingLoadable.Parser<Long> parser, String data) throws IOException {
    long actual = parser.parse(null, new ByteArrayInputStream(Util.getUtf8Bytes(data)));
    assertThat(actual).isEqualTo(expected);
  }
}
