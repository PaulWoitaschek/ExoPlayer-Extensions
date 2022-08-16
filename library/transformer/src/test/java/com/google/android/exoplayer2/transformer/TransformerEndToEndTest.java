/*
 * Copyright 2020 The Android Open Source Project
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

import static com.google.android.exoplayer2.transformer.Transformer.PROGRESS_STATE_AVAILABLE;
import static com.google.android.exoplayer2.transformer.Transformer.PROGRESS_STATE_NO_TRANSFORMATION;
import static com.google.android.exoplayer2.transformer.Transformer.PROGRESS_STATE_UNAVAILABLE;
import static com.google.android.exoplayer2.transformer.Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.testutil.DumpFileAsserts;
import com.google.android.exoplayer2.testutil.FakeClock;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowMediaCodec;

/** End-to-end test for {@link Transformer}. */
@RunWith(AndroidJUnit4.class)
public final class TransformerEndToEndTest {
  // TODO(b/214973843): Disable fallback for all tests that aren't specifically testing fallback.

  private static final String URI_PREFIX = "asset:///media/";
  private static final String FILE_VIDEO_ONLY = "mp4/sample_18byte_nclx_colr.mp4";
  private static final String FILE_AUDIO_VIDEO = "mp4/sample.mp4";
  private static final String FILE_WITH_SUBTITLES = "mkv/sample_with_srt.mkv";
  private static final String FILE_WITH_SEF_SLOW_MOTION = "mp4/sample_sef_slow_motion.mp4";
  private static final String FILE_AUDIO_UNSUPPORTED_BY_DECODER = "amr/sample_wb.amr";
  private static final String FILE_AUDIO_UNSUPPORTED_BY_ENCODER = "amr/sample_nb.amr";
  private static final String FILE_AUDIO_UNSUPPORTED_BY_MUXER = "mp4/sample_ac3.mp4";
  private static final String FILE_UNKNOWN_DURATION = "mp4/sample_fragmented.mp4";
  public static final String DUMP_FILE_OUTPUT_DIRECTORY = "transformerdumps";
  public static final String DUMP_FILE_EXTENSION = "dump";

  private Context context;
  private String outputPath;
  private TestMuxer testMuxer;
  private FakeClock clock;
  private ProgressHolder progressHolder;

  @Before
  public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    outputPath = Util.createTempFile(context, "TransformerTest").getPath();
    clock = new FakeClock(/* isAutoAdvancing= */ true);
    progressHolder = new ProgressHolder();
    createEncodersAndDecoders();
  }

  @After
  public void tearDown() throws Exception {
    Files.delete(Paths.get(outputPath));
    removeEncodersAndDecoders();
  }

  @Test
  public void startTransformation_videoOnlyPassthrough_completesSuccessfully() throws Exception {
    Transformer transformer = createTransformerBuilder().build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_VIDEO_ONLY);

    transformer.startTransformation(mediaItem, outputPath);
    TransformerTestRunner.runUntilCompleted(transformer);

    DumpFileAsserts.assertOutput(context, testMuxer, getDumpFileName(FILE_VIDEO_ONLY));
  }

  @Test
  public void startTransformation_audioOnlyPassthrough_completesSuccessfully() throws Exception {
    Transformer transformer = createTransformerBuilder().build();

    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_AUDIO_UNSUPPORTED_BY_ENCODER);

    transformer.startTransformation(mediaItem, outputPath);
    TransformerTestRunner.runUntilCompleted(transformer);

    DumpFileAsserts.assertOutput(
        context, testMuxer, getDumpFileName(FILE_AUDIO_UNSUPPORTED_BY_ENCODER));
  }

  @Test
  public void startTransformation_audioOnlyTranscoding_completesSuccessfully() throws Exception {
    Transformer transformer =
        createTransformerBuilder()
            .setTransformationRequest(
                new TransformationRequest.Builder()
                    .setAudioMimeType(MimeTypes.AUDIO_AAC) // supported by encoder and muxer
                    .build())
            .build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_AUDIO_UNSUPPORTED_BY_ENCODER);

    transformer.startTransformation(mediaItem, outputPath);
    TransformerTestRunner.runUntilCompleted(transformer);

    DumpFileAsserts.assertOutput(
        context, testMuxer, getDumpFileName(FILE_AUDIO_UNSUPPORTED_BY_ENCODER + ".aac"));
  }

  @Test
  public void startTransformation_audioAndVideo_completesSuccessfully() throws Exception {
    Transformer transformer = createTransformerBuilder().build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_AUDIO_VIDEO);

    transformer.startTransformation(mediaItem, outputPath);
    TransformerTestRunner.runUntilCompleted(transformer);

    DumpFileAsserts.assertOutput(context, testMuxer, getDumpFileName(FILE_AUDIO_VIDEO));
  }

  @Test
  public void startTransformation_withSubtitles_completesSuccessfully() throws Exception {
    Transformer transformer =
        createTransformerBuilder()
            .setTransformationRequest(
                new TransformationRequest.Builder().setAudioMimeType(MimeTypes.AUDIO_AAC).build())
            .build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_WITH_SUBTITLES);

    transformer.startTransformation(mediaItem, outputPath);
    TransformerTestRunner.runUntilCompleted(transformer);

    DumpFileAsserts.assertOutput(context, testMuxer, getDumpFileName(FILE_WITH_SUBTITLES));
  }

  @Test
  public void startTransformation_successiveTransformations_completesSuccessfully()
      throws Exception {
    Transformer transformer = createTransformerBuilder().build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_AUDIO_VIDEO);

    // Transform first media item.
    transformer.startTransformation(mediaItem, outputPath);
    TransformerTestRunner.runUntilCompleted(transformer);
    Files.delete(Paths.get(outputPath));

    // Transform second media item.
    transformer.startTransformation(mediaItem, outputPath);
    TransformerTestRunner.runUntilCompleted(transformer);

    DumpFileAsserts.assertOutput(context, testMuxer, getDumpFileName(FILE_AUDIO_VIDEO));
  }

  @Test
  public void startTransformation_concurrentTransformations_throwsError() throws Exception {
    Transformer transformer = createTransformerBuilder().build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_VIDEO_ONLY);

    transformer.startTransformation(mediaItem, outputPath);

    assertThrows(
        IllegalStateException.class, () -> transformer.startTransformation(mediaItem, outputPath));
  }

  @Test
  public void startTransformation_removeAudio_completesSuccessfully() throws Exception {
    Transformer transformer = createTransformerBuilder().setRemoveAudio(true).build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_AUDIO_VIDEO);

    transformer.startTransformation(mediaItem, outputPath);
    TransformerTestRunner.runUntilCompleted(transformer);

    DumpFileAsserts.assertOutput(
        context, testMuxer, getDumpFileName(FILE_AUDIO_VIDEO + ".noaudio"));
  }

  @Test
  public void startTransformation_removeVideo_completesSuccessfully() throws Exception {
    Transformer transformer = createTransformerBuilder().setRemoveVideo(true).build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_AUDIO_VIDEO);

    transformer.startTransformation(mediaItem, outputPath);
    TransformerTestRunner.runUntilCompleted(transformer);

    DumpFileAsserts.assertOutput(
        context, testMuxer, getDumpFileName(FILE_AUDIO_VIDEO + ".novideo"));
  }

  @Test
  public void startTransformation_withMultipleListeners_callsEachOnCompletion() throws Exception {
    Transformer.Listener mockListener1 = mock(Transformer.Listener.class);
    Transformer.Listener mockListener2 = mock(Transformer.Listener.class);
    Transformer.Listener mockListener3 = mock(Transformer.Listener.class);
    Transformer transformer =
        createTransformerBuilder()
            .addListener(mockListener1)
            .addListener(mockListener2)
            .addListener(mockListener3)
            .build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_AUDIO_VIDEO);

    transformer.startTransformation(mediaItem, outputPath);
    TransformerTestRunner.runUntilCompleted(transformer);

    verify(mockListener1, times(1)).onTransformationCompleted(mediaItem);
    verify(mockListener2, times(1)).onTransformationCompleted(mediaItem);
    verify(mockListener3, times(1)).onTransformationCompleted(mediaItem);
  }

  @Test
  public void startTransformation_withMultipleListeners_callsEachOnError() throws Exception {
    Transformer.Listener mockListener1 = mock(Transformer.Listener.class);
    Transformer.Listener mockListener2 = mock(Transformer.Listener.class);
    Transformer.Listener mockListener3 = mock(Transformer.Listener.class);
    Transformer transformer =
        createTransformerBuilder()
            .addListener(mockListener1)
            .addListener(mockListener2)
            .addListener(mockListener3)
            .setTransformationRequest( // Request transcoding so that decoder is used.
                new TransformationRequest.Builder().setAudioMimeType(MimeTypes.AUDIO_AAC).build())
            .build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_AUDIO_UNSUPPORTED_BY_DECODER);

    transformer.startTransformation(mediaItem, outputPath);
    TransformationException exception = TransformerTestRunner.runUntilError(transformer);

    verify(mockListener1, times(1)).onTransformationError(mediaItem, exception);
    verify(mockListener2, times(1)).onTransformationError(mediaItem, exception);
    verify(mockListener3, times(1)).onTransformationError(mediaItem, exception);
  }

  @Test
  public void startTransformation_withMultipleListeners_callsEachOnFallback() throws Exception {
    Transformer.Listener mockListener1 = mock(Transformer.Listener.class);
    Transformer.Listener mockListener2 = mock(Transformer.Listener.class);
    Transformer.Listener mockListener3 = mock(Transformer.Listener.class);
    TransformationRequest originalTransformationRequest =
        new TransformationRequest.Builder().build();
    TransformationRequest fallbackTransformationRequest =
        new TransformationRequest.Builder().setAudioMimeType(MimeTypes.AUDIO_AAC).build();
    Transformer transformer =
        createTransformerBuilder()
            .addListener(mockListener1)
            .addListener(mockListener2)
            .addListener(mockListener3)
            .build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_AUDIO_UNSUPPORTED_BY_MUXER);

    transformer.startTransformation(mediaItem, outputPath);
    TransformerTestRunner.runUntilCompleted(transformer);

    verify(mockListener1, times(1))
        .onFallbackApplied(mediaItem, originalTransformationRequest, fallbackTransformationRequest);
    verify(mockListener2, times(1))
        .onFallbackApplied(mediaItem, originalTransformationRequest, fallbackTransformationRequest);
    verify(mockListener3, times(1))
        .onFallbackApplied(mediaItem, originalTransformationRequest, fallbackTransformationRequest);
  }

  @Test
  public void startTransformation_afterBuildUponWithListenerRemoved_onlyCallsRemainingListeners()
      throws Exception {
    Transformer.Listener mockListener1 = mock(Transformer.Listener.class);
    Transformer.Listener mockListener2 = mock(Transformer.Listener.class);
    Transformer.Listener mockListener3 = mock(Transformer.Listener.class);
    Transformer transformer1 =
        createTransformerBuilder()
            .addListener(mockListener1)
            .addListener(mockListener2)
            .addListener(mockListener3)
            .build();
    Transformer transformer2 = transformer1.buildUpon().removeListener(mockListener2).build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_AUDIO_VIDEO);

    transformer2.startTransformation(mediaItem, outputPath);
    TransformerTestRunner.runUntilCompleted(transformer2);

    verify(mockListener1, times(1)).onTransformationCompleted(mediaItem);
    verify(mockListener2, never()).onTransformationCompleted(mediaItem);
    verify(mockListener3, times(1)).onTransformationCompleted(mediaItem);
  }

  @Test
  public void startTransformation_flattenForSlowMotion_completesSuccessfully() throws Exception {
    Transformer transformer =
        createTransformerBuilder()
            .setTransformationRequest(
                new TransformationRequest.Builder().setFlattenForSlowMotion(true).build())
            .build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_WITH_SEF_SLOW_MOTION);

    transformer.startTransformation(mediaItem, outputPath);
    TransformerTestRunner.runUntilCompleted(transformer);

    DumpFileAsserts.assertOutput(context, testMuxer, getDumpFileName(FILE_WITH_SEF_SLOW_MOTION));
  }

  @Test
  public void startTransformation_withAudioEncoderFormatUnsupported_completesWithError()
      throws Exception {
    Transformer transformer =
        createTransformerBuilder()
            .setTransformationRequest(
                new TransformationRequest.Builder()
                    .setAudioMimeType(
                        MimeTypes.AUDIO_AMR_NB) // unsupported by encoder, supported by muxer
                    .build())
            .build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_AUDIO_UNSUPPORTED_BY_MUXER);

    transformer.startTransformation(mediaItem, outputPath);
    TransformationException exception = TransformerTestRunner.runUntilError(transformer);

    assertThat(exception).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(exception.errorCode)
        .isEqualTo(TransformationException.ERROR_CODE_OUTPUT_FORMAT_UNSUPPORTED);
  }

  @Test
  public void startTransformation_withAudioDecoderFormatUnsupported_completesWithError()
      throws Exception {
    Transformer transformer =
        createTransformerBuilder()
            .setTransformationRequest(
                new TransformationRequest.Builder()
                    .setAudioMimeType(MimeTypes.AUDIO_AAC) // supported by encoder and muxer
                    .build())
            .build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_AUDIO_UNSUPPORTED_BY_DECODER);

    transformer.startTransformation(mediaItem, outputPath);
    TransformationException exception = TransformerTestRunner.runUntilError(transformer);

    assertThat(exception).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(exception.errorCode)
        .isEqualTo(TransformationException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED);
  }

  @Test
  public void startTransformation_withVideoEncoderFormatUnsupported_completesWithError()
      throws Exception {
    Transformer transformer =
        createTransformerBuilder()
            .setTransformationRequest(
                new TransformationRequest.Builder()
                    .setVideoMimeType(MimeTypes.VIDEO_H263) // unsupported encoder MIME type
                    .build())
            .build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_VIDEO_ONLY);

    transformer.startTransformation(mediaItem, outputPath);
    TransformationException exception = TransformerTestRunner.runUntilError(transformer);

    assertThat(exception).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(exception.errorCode)
        .isEqualTo(TransformationException.ERROR_CODE_OUTPUT_FORMAT_UNSUPPORTED);
  }

  @Test
  public void startTransformation_withIoError_completesWithError() throws Exception {
    Transformer transformer = createTransformerBuilder().build();
    MediaItem mediaItem = MediaItem.fromUri("asset:///non-existing-path.mp4");

    transformer.startTransformation(mediaItem, outputPath);
    TransformationException exception = TransformerTestRunner.runUntilError(transformer);

    assertThat(exception).hasCauseThat().hasCauseThat().isInstanceOf(IOException.class);
    assertThat(exception.errorCode).isEqualTo(TransformationException.ERROR_CODE_IO_FILE_NOT_FOUND);
  }

  @Test
  public void startTransformation_withAudioMuxerFormatFallback_completesSuccessfully()
      throws Exception {
    Transformer.Listener mockListener = mock(Transformer.Listener.class);
    TransformationRequest originalTransformationRequest =
        new TransformationRequest.Builder().build();
    TransformationRequest fallbackTransformationRequest =
        new TransformationRequest.Builder().setAudioMimeType(MimeTypes.AUDIO_AAC).build();
    Transformer transformer = createTransformerBuilder().addListener(mockListener).build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_AUDIO_UNSUPPORTED_BY_MUXER);

    transformer.startTransformation(mediaItem, outputPath);
    TransformerTestRunner.runUntilCompleted(transformer);

    DumpFileAsserts.assertOutput(
        context, testMuxer, getDumpFileName(FILE_AUDIO_UNSUPPORTED_BY_MUXER + ".fallback"));
    verify(mockListener, times(1))
        .onFallbackApplied(mediaItem, originalTransformationRequest, fallbackTransformationRequest);
  }

  // TODO(b/214012830): Add a test to check that the correct exception is thrown when the muxer
  // doesn't support the output sample MIME type inferred from the input once it is possible to
  // disable fallback.

  @Test
  public void startTransformation_afterCancellation_completesSuccessfully() throws Exception {
    Transformer transformer = createTransformerBuilder().build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_AUDIO_VIDEO);

    transformer.startTransformation(mediaItem, outputPath);
    transformer.cancel();
    Files.delete(Paths.get(outputPath));

    // This would throw if the previous transformation had not been cancelled.
    transformer.startTransformation(mediaItem, outputPath);
    TransformerTestRunner.runUntilCompleted(transformer);

    DumpFileAsserts.assertOutput(context, testMuxer, getDumpFileName(FILE_AUDIO_VIDEO));
  }

  @Test
  public void startTransformation_fromSpecifiedThread_completesSuccessfully() throws Exception {
    HandlerThread anotherThread = new HandlerThread("AnotherThread");
    anotherThread.start();
    Looper looper = anotherThread.getLooper();
    Transformer transformer = createTransformerBuilder().setLooper(looper).build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_AUDIO_VIDEO);
    AtomicReference<Exception> exception = new AtomicReference<>();
    CountDownLatch countDownLatch = new CountDownLatch(1);

    new Handler(looper)
        .post(
            () -> {
              try {
                transformer.startTransformation(mediaItem, outputPath);
                TransformerTestRunner.runUntilCompleted(transformer);
              } catch (Exception e) {
                exception.set(e);
              } finally {
                countDownLatch.countDown();
              }
            });
    countDownLatch.await();

    assertThat(exception.get()).isNull();
    DumpFileAsserts.assertOutput(context, testMuxer, getDumpFileName(FILE_AUDIO_VIDEO));
  }

  @Test
  public void startTransformation_fromWrongThread_throwsError() throws Exception {
    Transformer transformer = createTransformerBuilder().build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_AUDIO_VIDEO);
    HandlerThread anotherThread = new HandlerThread("AnotherThread");
    AtomicReference<IllegalStateException> illegalStateException = new AtomicReference<>();
    CountDownLatch countDownLatch = new CountDownLatch(1);

    anotherThread.start();
    new Handler(anotherThread.getLooper())
        .post(
            () -> {
              try {
                transformer.startTransformation(mediaItem, outputPath);
              } catch (IOException e) {
                // Do nothing.
              } catch (IllegalStateException e) {
                illegalStateException.set(e);
              } finally {
                countDownLatch.countDown();
              }
            });
    countDownLatch.await();

    assertThat(illegalStateException.get()).isNotNull();
  }

  @Test
  public void getProgress_knownDuration_returnsConsistentStates() throws Exception {
    Transformer transformer = createTransformerBuilder().build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_VIDEO_ONLY);
    AtomicInteger previousProgressState =
        new AtomicInteger(PROGRESS_STATE_WAITING_FOR_AVAILABILITY);
    AtomicBoolean foundInconsistentState = new AtomicBoolean();
    Handler progressHandler =
        new Handler(Looper.myLooper()) {
          @Override
          public void handleMessage(Message msg) {
            @Transformer.ProgressState int progressState = transformer.getProgress(progressHolder);
            if (progressState == PROGRESS_STATE_UNAVAILABLE) {
              foundInconsistentState.set(true);
              return;
            }
            switch (previousProgressState.get()) {
              case PROGRESS_STATE_WAITING_FOR_AVAILABILITY:
                break;
              case PROGRESS_STATE_AVAILABLE:
                if (progressState == PROGRESS_STATE_WAITING_FOR_AVAILABILITY) {
                  foundInconsistentState.set(true);
                  return;
                }
                break;
              case PROGRESS_STATE_NO_TRANSFORMATION:
                if (progressState != PROGRESS_STATE_NO_TRANSFORMATION) {
                  foundInconsistentState.set(true);
                  return;
                }
                break;
              default:
                throw new IllegalStateException();
            }
            previousProgressState.set(progressState);
            sendEmptyMessage(0);
          }
        };

    transformer.startTransformation(mediaItem, outputPath);
    progressHandler.sendEmptyMessage(0);
    TransformerTestRunner.runUntilCompleted(transformer);

    assertThat(foundInconsistentState.get()).isFalse();
  }

  @Test
  public void getProgress_knownDuration_givesIncreasingPercentages() throws Exception {
    Transformer transformer = createTransformerBuilder().build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_VIDEO_ONLY);
    List<Integer> progresses = new ArrayList<>();
    Handler progressHandler =
        new Handler(Looper.myLooper()) {
          @Override
          public void handleMessage(Message msg) {
            @Transformer.ProgressState int progressState = transformer.getProgress(progressHolder);
            if (progressState == PROGRESS_STATE_NO_TRANSFORMATION) {
              return;
            }
            if (progressState != PROGRESS_STATE_WAITING_FOR_AVAILABILITY
                && (progresses.isEmpty()
                    || Iterables.getLast(progresses) != progressHolder.progress)) {
              progresses.add(progressHolder.progress);
            }
            sendEmptyMessage(0);
          }
        };

    transformer.startTransformation(mediaItem, outputPath);
    progressHandler.sendEmptyMessage(0);
    TransformerTestRunner.runUntilCompleted(transformer);

    assertThat(progresses).isInOrder();
    if (!progresses.isEmpty()) {
      // The progress list could be empty if the transformation ends before any progress can be
      // retrieved.
      assertThat(progresses.get(0)).isAtLeast(0);
      assertThat(Iterables.getLast(progresses)).isLessThan(100);
    }
  }

  @Test
  public void getProgress_noCurrentTransformation_returnsNoTransformation() throws Exception {
    Transformer transformer = createTransformerBuilder().build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_VIDEO_ONLY);

    @Transformer.ProgressState int stateBeforeTransform = transformer.getProgress(progressHolder);
    transformer.startTransformation(mediaItem, outputPath);
    TransformerTestRunner.runUntilCompleted(transformer);
    @Transformer.ProgressState int stateAfterTransform = transformer.getProgress(progressHolder);

    assertThat(stateBeforeTransform).isEqualTo(Transformer.PROGRESS_STATE_NO_TRANSFORMATION);
    assertThat(stateAfterTransform).isEqualTo(Transformer.PROGRESS_STATE_NO_TRANSFORMATION);
  }

  @Test
  public void getProgress_unknownDuration_returnsConsistentStates() throws Exception {
    Transformer transformer = createTransformerBuilder().build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_UNKNOWN_DURATION);
    AtomicInteger previousProgressState =
        new AtomicInteger(PROGRESS_STATE_WAITING_FOR_AVAILABILITY);
    AtomicBoolean foundInconsistentState = new AtomicBoolean();
    Handler progressHandler =
        new Handler(Looper.myLooper()) {
          @Override
          public void handleMessage(Message msg) {
            @Transformer.ProgressState int progressState = transformer.getProgress(progressHolder);
            switch (previousProgressState.get()) {
              case PROGRESS_STATE_WAITING_FOR_AVAILABILITY:
                break;
              case PROGRESS_STATE_UNAVAILABLE:
              case PROGRESS_STATE_AVAILABLE: // See [Internal: b/176145097].
                if (progressState == PROGRESS_STATE_WAITING_FOR_AVAILABILITY) {
                  foundInconsistentState.set(true);
                  return;
                }
                break;
              case PROGRESS_STATE_NO_TRANSFORMATION:
                if (progressState != PROGRESS_STATE_NO_TRANSFORMATION) {
                  foundInconsistentState.set(true);
                  return;
                }
                break;
              default:
                throw new IllegalStateException();
            }
            previousProgressState.set(progressState);
            sendEmptyMessage(0);
          }
        };

    transformer.startTransformation(mediaItem, outputPath);
    progressHandler.sendEmptyMessage(0);
    TransformerTestRunner.runUntilCompleted(transformer);

    assertThat(foundInconsistentState.get()).isFalse();
  }

  @Test
  public void getProgress_fromWrongThread_throwsError() throws Exception {
    Transformer transformer = createTransformerBuilder().build();
    HandlerThread anotherThread = new HandlerThread("AnotherThread");
    AtomicReference<IllegalStateException> illegalStateException = new AtomicReference<>();
    CountDownLatch countDownLatch = new CountDownLatch(1);

    anotherThread.start();
    new Handler(anotherThread.getLooper())
        .post(
            () -> {
              try {
                transformer.getProgress(progressHolder);
              } catch (IllegalStateException e) {
                illegalStateException.set(e);
              } finally {
                countDownLatch.countDown();
              }
            });
    countDownLatch.await();

    assertThat(illegalStateException.get()).isNotNull();
  }

  @Test
  public void cancel_afterCompletion_doesNotThrow() throws Exception {
    Transformer transformer = createTransformerBuilder().build();
    MediaItem mediaItem = MediaItem.fromUri(URI_PREFIX + FILE_VIDEO_ONLY);

    transformer.startTransformation(mediaItem, outputPath);
    TransformerTestRunner.runUntilCompleted(transformer);
    transformer.cancel();
  }

  @Test
  public void cancel_fromWrongThread_throwsError() throws Exception {
    Transformer transformer = createTransformerBuilder().build();
    HandlerThread anotherThread = new HandlerThread("AnotherThread");
    AtomicReference<IllegalStateException> illegalStateException = new AtomicReference<>();
    CountDownLatch countDownLatch = new CountDownLatch(1);

    anotherThread.start();
    new Handler(anotherThread.getLooper())
        .post(
            () -> {
              try {
                transformer.cancel();
              } catch (IllegalStateException e) {
                illegalStateException.set(e);
              } finally {
                countDownLatch.countDown();
              }
            });
    countDownLatch.await();

    assertThat(illegalStateException.get()).isNotNull();
  }

  private Transformer.Builder createTransformerBuilder() {
    return new Transformer.Builder(context).setClock(clock).setMuxerFactory(new TestMuxerFactory());
  }

  private static void createEncodersAndDecoders() {
    ShadowMediaCodec.CodecConfig codecConfig =
        new ShadowMediaCodec.CodecConfig(
            /* inputBufferSize= */ 10_000,
            /* outputBufferSize= */ 10_000,
            /* codec= */ (in, out) -> out.put(in));
    ShadowMediaCodec.addDecoder(MimeTypes.AUDIO_AAC, codecConfig);
    ShadowMediaCodec.addDecoder(MimeTypes.AUDIO_AC3, codecConfig);
    ShadowMediaCodec.addDecoder(MimeTypes.AUDIO_AMR_NB, codecConfig);
    ShadowMediaCodec.addEncoder(MimeTypes.AUDIO_AAC, codecConfig);

    ShadowMediaCodec.CodecConfig throwingCodecConfig =
        new ShadowMediaCodec.CodecConfig(
            /* inputBufferSize= */ 10_000,
            /* outputBufferSize= */ 10_000,
            new ShadowMediaCodec.CodecConfig.Codec() {

              @Override
              public void process(ByteBuffer in, ByteBuffer out) {
                out.put(in);
              }

              @Override
              public void onConfigured(
                  MediaFormat format,
                  @Nullable Surface surface,
                  @Nullable MediaCrypto crypto,
                  int flags) {
                throw new IllegalArgumentException("Format unsupported");
              }
            });

    ShadowMediaCodec.addDecoder(MimeTypes.AUDIO_AMR_WB, throwingCodecConfig);
    ShadowMediaCodec.addEncoder(MimeTypes.AUDIO_AMR_NB, throwingCodecConfig);
    ShadowMediaCodec.addEncoder(MimeTypes.VIDEO_H263, throwingCodecConfig);
  }

  private static void removeEncodersAndDecoders() {
    ShadowMediaCodec.clearCodecs();
  }

  private static String getDumpFileName(String originalFileName) {
    return DUMP_FILE_OUTPUT_DIRECTORY + '/' + originalFileName + '.' + DUMP_FILE_EXTENSION;
  }

  private final class TestMuxerFactory implements Muxer.Factory {

    private final Muxer.Factory frameworkMuxerFactory;

    public TestMuxerFactory() {
      frameworkMuxerFactory = new FrameworkMuxer.Factory();
    }

    @Override
    public Muxer create(String path, String outputMimeType) throws IOException {
      testMuxer = new TestMuxer(path, outputMimeType, frameworkMuxerFactory);
      return testMuxer;
    }

    @Override
    public Muxer create(ParcelFileDescriptor parcelFileDescriptor, String outputMimeType)
        throws IOException {
      testMuxer =
          new TestMuxer(
              "FD:" + parcelFileDescriptor.getFd(), outputMimeType, frameworkMuxerFactory);
      return testMuxer;
    }

    @Override
    public boolean supportsOutputMimeType(String mimeType) {
      return true;
    }

    @Override
    public boolean supportsSampleMimeType(String sampleMimeType, String outputMimeType) {
      return frameworkMuxerFactory.supportsSampleMimeType(sampleMimeType, outputMimeType);
    }

    @Override
    public ImmutableList<String> getSupportedSampleMimeTypes(
        @C.TrackType int trackType, String containerMimeType) {
      return frameworkMuxerFactory.getSupportedSampleMimeTypes(trackType, containerMimeType);
    }
  }
}
