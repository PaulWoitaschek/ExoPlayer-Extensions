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
package com.google.android.exoplayer2.audio;

import static java.lang.Math.max;
import static java.lang.Math.min;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.PlaybackParams;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Pair;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.audio.AudioProcessor.UnhandledAudioFormatException;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Plays audio data. The implementation delegates to an {@link AudioTrack} and handles playback
 * position smoothing, non-blocking writes and reconfiguration.
 *
 * <p>If tunneling mode is enabled, care must be taken that audio processors do not output buffers
 * with a different duration than their input, and buffer processors must produce output
 * corresponding to their last input immediately after that input is queued. This means that, for
 * example, speed adjustment is not possible while using tunneling.
 */
public final class DefaultAudioSink implements AudioSink {

  /**
   * Thrown when the audio track has provided a spurious timestamp, if {@link
   * #failOnSpuriousAudioTimestamp} is set.
   */
  public static final class InvalidAudioTrackTimestampException extends RuntimeException {

    /**
     * Creates a new invalid timestamp exception with the specified message.
     *
     * @param message The detail message for this exception.
     */
    private InvalidAudioTrackTimestampException(String message) {
      super(message);
    }

  }

  /**
   * Provides a chain of audio processors, which are used for any user-defined processing and
   * applying playback parameters (if supported). Because applying playback parameters can skip and
   * stretch/compress audio, the sink will query the chain for information on how to transform its
   * output position to map it onto a media position, via {@link #getMediaDuration(long)} and {@link
   * #getSkippedOutputFrameCount()}.
   */
  public interface AudioProcessorChain {

    /**
     * Returns the fixed chain of audio processors that will process audio. This method is called
     * once during initialization, but audio processors may change state to become active/inactive
     * during playback.
     */
    AudioProcessor[] getAudioProcessors();

    /**
     * Configures audio processors to apply the specified playback parameters immediately, returning
     * the new playback parameters, which may differ from those passed in. Only called when
     * processors have no input pending.
     *
     * @param playbackParameters The playback parameters to try to apply.
     * @return The playback parameters that were actually applied.
     */
    PlaybackParameters applyPlaybackParameters(PlaybackParameters playbackParameters);

    /**
     * Configures audio processors to apply whether to skip silences immediately, returning the new
     * value. Only called when processors have no input pending.
     *
     * @param skipSilenceEnabled Whether silences should be skipped in the audio stream.
     * @return The new value.
     */
    boolean applySkipSilenceEnabled(boolean skipSilenceEnabled);

    /**
     * Returns the media duration corresponding to the specified playout duration, taking speed
     * adjustment due to audio processing into account.
     *
     * <p>The scaling performed by this method will use the actual playback speed achieved by the
     * audio processor chain, on average, since it was last flushed. This may differ very slightly
     * from the target playback speed.
     *
     * @param playoutDuration The playout duration to scale.
     * @return The corresponding media duration, in the same units as {@code duration}.
     */
    long getMediaDuration(long playoutDuration);

    /**
     * Returns the number of output audio frames skipped since the audio processors were last
     * flushed.
     */
    long getSkippedOutputFrameCount();
  }

  /**
   * The default audio processor chain, which applies a (possibly empty) chain of user-defined audio
   * processors followed by {@link SilenceSkippingAudioProcessor} and {@link SonicAudioProcessor}.
   */
  public static class DefaultAudioProcessorChain implements AudioProcessorChain {

    private final AudioProcessor[] audioProcessors;
    private final SilenceSkippingAudioProcessor silenceSkippingAudioProcessor;
    private final SonicAudioProcessor sonicAudioProcessor;

    /**
     * Creates a new default chain of audio processors, with the user-defined {@code
     * audioProcessors} applied before silence skipping and speed adjustment processors.
     */
    public DefaultAudioProcessorChain(AudioProcessor... audioProcessors) {
      this(audioProcessors, new SilenceSkippingAudioProcessor(), new SonicAudioProcessor());
    }

    /**
     * Creates a new default chain of audio processors, with the user-defined {@code
     * audioProcessors} applied before silence skipping and speed adjustment processors.
     */
    public DefaultAudioProcessorChain(
        AudioProcessor[] audioProcessors,
        SilenceSkippingAudioProcessor silenceSkippingAudioProcessor,
        SonicAudioProcessor sonicAudioProcessor) {
      // The passed-in type may be more specialized than AudioProcessor[], so allocate a new array
      // rather than using Arrays.copyOf.
      this.audioProcessors = new AudioProcessor[audioProcessors.length + 2];
      System.arraycopy(
          /* src= */ audioProcessors,
          /* srcPos= */ 0,
          /* dest= */ this.audioProcessors,
          /* destPos= */ 0,
          /* length= */ audioProcessors.length);
      this.silenceSkippingAudioProcessor = silenceSkippingAudioProcessor;
      this.sonicAudioProcessor = sonicAudioProcessor;
      this.audioProcessors[audioProcessors.length] = silenceSkippingAudioProcessor;
      this.audioProcessors[audioProcessors.length + 1] = sonicAudioProcessor;
    }

    @Override
    public AudioProcessor[] getAudioProcessors() {
      return audioProcessors;
    }

    @Override
    public PlaybackParameters applyPlaybackParameters(PlaybackParameters playbackParameters) {
      sonicAudioProcessor.setSpeed(playbackParameters.speed);
      sonicAudioProcessor.setPitch(playbackParameters.pitch);
      return playbackParameters;
    }

    @Override
    public boolean applySkipSilenceEnabled(boolean skipSilenceEnabled) {
      silenceSkippingAudioProcessor.setEnabled(skipSilenceEnabled);
      return skipSilenceEnabled;
    }

    @Override
    public long getMediaDuration(long playoutDuration) {
      return sonicAudioProcessor.getMediaDuration(playoutDuration);
    }

    @Override
    public long getSkippedOutputFrameCount() {
      return silenceSkippingAudioProcessor.getSkippedFrames();
    }
  }

  /** The default playback speed. */
  public static final float DEFAULT_PLAYBACK_SPEED = 1f;
  /** The minimum allowed playback speed. Lower values will be constrained to fall in range. */
  public static final float MIN_PLAYBACK_SPEED = 0.1f;
  /** The maximum allowed playback speed. Higher values will be constrained to fall in range. */
  public static final float MAX_PLAYBACK_SPEED = 8f;
  /** The minimum allowed pitch factor. Lower values will be constrained to fall in range. */
  public static final float MIN_PITCH = 0.1f;
  /** The maximum allowed pitch factor. Higher values will be constrained to fall in range. */
  public static final float MAX_PITCH = 8f;

  /** The default skip silence flag. */
  private static final boolean DEFAULT_SKIP_SILENCE = false;

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({OUTPUT_MODE_PCM, OUTPUT_MODE_OFFLOAD, OUTPUT_MODE_PASSTHROUGH})
  private @interface OutputMode {}

  private static final int OUTPUT_MODE_PCM = 0;
  private static final int OUTPUT_MODE_OFFLOAD = 1;
  private static final int OUTPUT_MODE_PASSTHROUGH = 2;

  /** A minimum length for the {@link AudioTrack} buffer, in microseconds. */
  private static final long MIN_BUFFER_DURATION_US = 250_000;
  /** A maximum length for the {@link AudioTrack} buffer, in microseconds. */
  private static final long MAX_BUFFER_DURATION_US = 750_000;
  /** The length for passthrough {@link AudioTrack} buffers, in microseconds. */
  private static final long PASSTHROUGH_BUFFER_DURATION_US = 250_000;
  /** The length for offload {@link AudioTrack} buffers, in microseconds. */
  private static final long OFFLOAD_BUFFER_DURATION_US = 50_000_000;

  /**
   * A multiplication factor to apply to the minimum buffer size requested by the underlying {@link
   * AudioTrack}.
   */
  private static final int BUFFER_MULTIPLICATION_FACTOR = 4;
  /** To avoid underruns on some devices (e.g., Broadcom 7271), scale up the AC3 buffer duration. */
  private static final int AC3_BUFFER_MULTIPLICATION_FACTOR = 2;

  /**
   * @see AudioTrack#ERROR_BAD_VALUE
   */
  private static final int ERROR_BAD_VALUE = AudioTrack.ERROR_BAD_VALUE;
  /**
   * @see AudioTrack#MODE_STATIC
   */
  private static final int MODE_STATIC = AudioTrack.MODE_STATIC;
  /**
   * @see AudioTrack#MODE_STREAM
   */
  private static final int MODE_STREAM = AudioTrack.MODE_STREAM;
  /**
   * @see AudioTrack#STATE_INITIALIZED
   */
  private static final int STATE_INITIALIZED = AudioTrack.STATE_INITIALIZED;
  /**
   * @see AudioTrack#WRITE_NON_BLOCKING
   */
  @SuppressLint("InlinedApi")
  private static final int WRITE_NON_BLOCKING = AudioTrack.WRITE_NON_BLOCKING;

  private static final String TAG = "AudioTrack";

  /**
   * Whether to enable a workaround for an issue where an audio effect does not keep its session
   * active across releasing/initializing a new audio track, on platform builds where
   * {@link Util#SDK_INT} &lt; 21.
   * <p>
   * The flag must be set before creating a player.
   */
  public static boolean enablePreV21AudioSessionWorkaround = false;

  /**
   * Whether to throw an {@link InvalidAudioTrackTimestampException} when a spurious timestamp is
   * reported from {@link AudioTrack#getTimestamp}.
   * <p>
   * The flag must be set before creating a player. Should be set to {@code true} for testing and
   * debugging purposes only.
   */
  public static boolean failOnSpuriousAudioTimestamp = false;

  @Nullable private final AudioCapabilities audioCapabilities;
  private final AudioProcessorChain audioProcessorChain;
  private final boolean enableFloatOutput;
  private final ChannelMappingAudioProcessor channelMappingAudioProcessor;
  private final TrimmingAudioProcessor trimmingAudioProcessor;
  private final AudioProcessor[] toIntPcmAvailableAudioProcessors;
  private final AudioProcessor[] toFloatPcmAvailableAudioProcessors;
  private final ConditionVariable releasingConditionVariable;
  private final AudioTrackPositionTracker audioTrackPositionTracker;
  private final ArrayDeque<MediaPositionParameters> mediaPositionParametersCheckpoints;
  private final boolean enableAudioTrackPlaybackParams;
  private final boolean enableOffload;
  @MonotonicNonNull private StreamEventCallbackV29 offloadStreamEventCallbackV29;

  @Nullable private Listener listener;
  /**
   * Used to keep the audio session active on pre-V21 builds (see {@link #initializeAudioTrack()}).
   */
  @Nullable private AudioTrack keepSessionIdAudioTrack;

  @Nullable private Configuration pendingConfiguration;
  @MonotonicNonNull private Configuration configuration;
  @Nullable private AudioTrack audioTrack;

  private AudioAttributes audioAttributes;
  @Nullable private MediaPositionParameters afterDrainParameters;
  private MediaPositionParameters mediaPositionParameters;
  private PlaybackParameters audioTrackPlaybackParameters;

  @Nullable private ByteBuffer avSyncHeader;
  private int bytesUntilNextAvSync;

  private long submittedPcmBytes;
  private long submittedEncodedFrames;
  private long writtenPcmBytes;
  private long writtenEncodedFrames;
  private int framesPerEncodedSample;
  private boolean startMediaTimeUsNeedsSync;
  private boolean startMediaTimeUsNeedsInit;
  private long startMediaTimeUs;
  private float volume;

  private AudioProcessor[] activeAudioProcessors;
  private ByteBuffer[] outputBuffers;
  @Nullable private ByteBuffer inputBuffer;
  private int inputBufferAccessUnitCount;
  @Nullable private ByteBuffer outputBuffer;
  @MonotonicNonNull private byte[] preV21OutputBuffer;
  private int preV21OutputBufferOffset;
  private int drainingAudioProcessorIndex;
  private boolean handledEndOfStream;
  private boolean stoppedAudioTrack;

  private boolean playing;
  private int audioSessionId;
  private AuxEffectInfo auxEffectInfo;
  private boolean tunneling;
  private long lastFeedElapsedRealtimeMs;
  private boolean offloadDisabledUntilNextConfiguration;
  private boolean isWaitingForOffloadEndOfStreamHandled;

  /**
   * Creates a new default audio sink.
   *
   * @param audioCapabilities The audio capabilities for playback on this device. May be null if the
   *     default capabilities (no encoded audio passthrough support) should be assumed.
   * @param audioProcessors An array of {@link AudioProcessor}s that will process PCM audio before
   *     output. May be empty.
   */
  public DefaultAudioSink(
      @Nullable AudioCapabilities audioCapabilities, AudioProcessor[] audioProcessors) {
    this(audioCapabilities, audioProcessors, /* enableFloatOutput= */ false);
  }

  /**
   * Creates a new default audio sink, optionally using float output for high resolution PCM.
   *
   * @param audioCapabilities The audio capabilities for playback on this device. May be null if the
   *     default capabilities (no encoded audio passthrough support) should be assumed.
   * @param audioProcessors An array of {@link AudioProcessor}s that will process PCM audio before
   *     output. May be empty.
   * @param enableFloatOutput Whether to enable 32-bit float output. Where possible, 32-bit float
   *     output will be used if the input is 32-bit float, and also if the input is high resolution
   *     (24-bit or 32-bit) integer PCM. Audio processing (for example, speed adjustment) will not
   *     be available when float output is in use.
   */
  public DefaultAudioSink(
      @Nullable AudioCapabilities audioCapabilities,
      AudioProcessor[] audioProcessors,
      boolean enableFloatOutput) {
    this(
        audioCapabilities,
        new DefaultAudioProcessorChain(audioProcessors),
        enableFloatOutput,
        /* enableAudioTrackPlaybackParams= */ false,
        /* enableOffload= */ false);
  }

  /**
   * Creates a new default audio sink, optionally using float output for high resolution PCM and
   * with the specified {@code audioProcessorChain}.
   *
   * @param audioCapabilities The audio capabilities for playback on this device. May be null if the
   *     default capabilities (no encoded audio passthrough support) should be assumed.
   * @param audioProcessorChain An {@link AudioProcessorChain} which is used to apply playback
   *     parameters adjustments. The instance passed in must not be reused in other sinks.
   * @param enableFloatOutput Whether to enable 32-bit float output. Where possible, 32-bit float
   *     output will be used if the input is 32-bit float, and also if the input is high resolution
   *     (24-bit or 32-bit) integer PCM. Float output is supported from API level 21. Audio
   *     processing (for example, speed adjustment) will not be available when float output is in
   *     use.
   * @param enableAudioTrackPlaybackParams Whether to enable setting playback speed using {@link
   *     android.media.AudioTrack#setPlaybackParams(PlaybackParams)}, if supported.
   * @param enableOffload Whether to enable audio offload. If an audio format can be both played
   *     with offload and encoded audio passthrough, it will be played in offload. Audio offload is
   *     supported from API level 29. Most Android devices can only support one offload {@link
   *     android.media.AudioTrack} at a time and can invalidate it at any time. Thus an app can
   *     never be guaranteed that it will be able to play in offload. Audio processing (for example,
   *     speed adjustment) will not be available when offload is in use.
   */
  public DefaultAudioSink(
      @Nullable AudioCapabilities audioCapabilities,
      AudioProcessorChain audioProcessorChain,
      boolean enableFloatOutput,
      boolean enableAudioTrackPlaybackParams,
      boolean enableOffload) {
    this.audioCapabilities = audioCapabilities;
    this.audioProcessorChain = Assertions.checkNotNull(audioProcessorChain);
    this.enableFloatOutput = Util.SDK_INT >= 21 && enableFloatOutput;
    this.enableAudioTrackPlaybackParams = Util.SDK_INT >= 23 && enableAudioTrackPlaybackParams;
    this.enableOffload = Util.SDK_INT >= 29 && enableOffload;
    releasingConditionVariable = new ConditionVariable(true);
    audioTrackPositionTracker = new AudioTrackPositionTracker(new PositionTrackerListener());
    channelMappingAudioProcessor = new ChannelMappingAudioProcessor();
    trimmingAudioProcessor = new TrimmingAudioProcessor();
    ArrayList<AudioProcessor> toIntPcmAudioProcessors = new ArrayList<>();
    Collections.addAll(
        toIntPcmAudioProcessors,
        new ResamplingAudioProcessor(),
        channelMappingAudioProcessor,
        trimmingAudioProcessor);
    Collections.addAll(toIntPcmAudioProcessors, audioProcessorChain.getAudioProcessors());
    toIntPcmAvailableAudioProcessors = toIntPcmAudioProcessors.toArray(new AudioProcessor[0]);
    toFloatPcmAvailableAudioProcessors = new AudioProcessor[] {new FloatResamplingAudioProcessor()};
    volume = 1f;
    audioAttributes = AudioAttributes.DEFAULT;
    audioSessionId = C.AUDIO_SESSION_ID_UNSET;
    auxEffectInfo = new AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f);
    mediaPositionParameters =
        new MediaPositionParameters(
            PlaybackParameters.DEFAULT,
            DEFAULT_SKIP_SILENCE,
            /* mediaTimeUs= */ 0,
            /* audioTrackPositionUs= */ 0);
    audioTrackPlaybackParameters = PlaybackParameters.DEFAULT;
    drainingAudioProcessorIndex = C.INDEX_UNSET;
    activeAudioProcessors = new AudioProcessor[0];
    outputBuffers = new ByteBuffer[0];
    mediaPositionParametersCheckpoints = new ArrayDeque<>();
  }

  // AudioSink implementation.

  @Override
  public void setListener(Listener listener) {
    this.listener = listener;
  }

  @Override
  public boolean supportsFormat(Format format) {
    return getFormatSupport(format) != SINK_FORMAT_UNSUPPORTED;
  }

  @Override
  @SinkFormatSupport
  public int getFormatSupport(Format format) {
    if (MimeTypes.AUDIO_RAW.equals(format.sampleMimeType)) {
      if (!Util.isEncodingLinearPcm(format.pcmEncoding)) {
        Log.w(TAG, "Invalid PCM encoding: " + format.pcmEncoding);
        return SINK_FORMAT_UNSUPPORTED;
      }
      if (format.pcmEncoding == C.ENCODING_PCM_16BIT
          || (enableFloatOutput && format.pcmEncoding == C.ENCODING_PCM_FLOAT)) {
        return SINK_FORMAT_SUPPORTED_DIRECTLY;
      }
      // We can resample all linear PCM encodings to 16-bit integer PCM, which AudioTrack is
      // guaranteed to support.
      return SINK_FORMAT_SUPPORTED_WITH_TRANSCODING;
    }
    if (enableOffload
        && !offloadDisabledUntilNextConfiguration
        && isOffloadedPlaybackSupported(format, audioAttributes)) {
      return SINK_FORMAT_SUPPORTED_DIRECTLY;
    }
    if (isPassthroughPlaybackSupported(format, audioCapabilities)) {
      return SINK_FORMAT_SUPPORTED_DIRECTLY;
    }
    return SINK_FORMAT_UNSUPPORTED;
  }

  @Override
  public long getCurrentPositionUs(boolean sourceEnded) {
    if (!isAudioTrackInitialized() || startMediaTimeUsNeedsInit) {
      return CURRENT_POSITION_NOT_SET;
    }
    long positionUs = audioTrackPositionTracker.getCurrentPositionUs(sourceEnded);
    positionUs = min(positionUs, configuration.framesToDurationUs(getWrittenFrames()));
    return applySkipping(applyMediaPositionParameters(positionUs));
  }

  @Override
  public void configure(Format inputFormat, int specifiedBufferSize, @Nullable int[] outputChannels)
      throws ConfigurationException {
    int inputPcmFrameSize;
    @Nullable AudioProcessor[] availableAudioProcessors;
    boolean canApplyPlaybackParameters;

    @OutputMode int outputMode;
    @C.Encoding int outputEncoding;
    int outputSampleRate;
    int outputChannelConfig;
    int outputPcmFrameSize;

    if (MimeTypes.AUDIO_RAW.equals(inputFormat.sampleMimeType)) {
      Assertions.checkArgument(Util.isEncodingLinearPcm(inputFormat.pcmEncoding));

      inputPcmFrameSize = Util.getPcmFrameSize(inputFormat.pcmEncoding, inputFormat.channelCount);
      boolean useFloatOutput =
          enableFloatOutput && Util.isEncodingHighResolutionPcm(inputFormat.pcmEncoding);
      availableAudioProcessors =
          useFloatOutput ? toFloatPcmAvailableAudioProcessors : toIntPcmAvailableAudioProcessors;
      canApplyPlaybackParameters = !useFloatOutput;

      trimmingAudioProcessor.setTrimFrameCount(
          inputFormat.encoderDelay, inputFormat.encoderPadding);

      if (Util.SDK_INT < 21 && inputFormat.channelCount == 8 && outputChannels == null) {
        // AudioTrack doesn't support 8 channel output before Android L. Discard the last two (side)
        // channels to give a 6 channel stream that is supported.
        outputChannels = new int[6];
        for (int i = 0; i < outputChannels.length; i++) {
          outputChannels[i] = i;
        }
      }
      channelMappingAudioProcessor.setChannelMap(outputChannels);

      AudioProcessor.AudioFormat outputFormat =
          new AudioProcessor.AudioFormat(
              inputFormat.sampleRate, inputFormat.channelCount, inputFormat.pcmEncoding);
      for (AudioProcessor audioProcessor : availableAudioProcessors) {
        try {
          AudioProcessor.AudioFormat nextFormat = audioProcessor.configure(outputFormat);
          if (audioProcessor.isActive()) {
            outputFormat = nextFormat;
          }
        } catch (UnhandledAudioFormatException e) {
          throw new ConfigurationException(e);
        }
      }

      outputMode = OUTPUT_MODE_PCM;
      outputEncoding = outputFormat.encoding;
      outputSampleRate = outputFormat.sampleRate;
      outputChannelConfig = Util.getAudioTrackChannelConfig(outputFormat.channelCount);
      outputPcmFrameSize = Util.getPcmFrameSize(outputEncoding, outputFormat.channelCount);
    } else {
      inputPcmFrameSize = C.LENGTH_UNSET;
      availableAudioProcessors = new AudioProcessor[0];
      canApplyPlaybackParameters = false;
      outputSampleRate = inputFormat.sampleRate;
      outputPcmFrameSize = C.LENGTH_UNSET;
      if (enableOffload && isOffloadedPlaybackSupported(inputFormat, audioAttributes)) {
        outputMode = OUTPUT_MODE_OFFLOAD;
        outputEncoding =
            MimeTypes.getEncoding(
                Assertions.checkNotNull(inputFormat.sampleMimeType), inputFormat.codecs);
        outputChannelConfig = Util.getAudioTrackChannelConfig(inputFormat.channelCount);
      } else {
        outputMode = OUTPUT_MODE_PASSTHROUGH;
        @Nullable
        Pair<Integer, Integer> encodingAndChannelConfig =
            getEncodingAndChannelConfigForPassthrough(inputFormat, audioCapabilities);
        if (encodingAndChannelConfig == null) {
          throw new ConfigurationException("Unable to configure passthrough for: " + inputFormat);
        }
        outputEncoding = encodingAndChannelConfig.first;
        outputChannelConfig = encodingAndChannelConfig.second;
      }
    }

    if (outputEncoding == C.ENCODING_INVALID) {
      throw new ConfigurationException(
          "Invalid output encoding (mode=" + outputMode + ") for: " + inputFormat);
    }
    if (outputChannelConfig == AudioFormat.CHANNEL_INVALID) {
      throw new ConfigurationException(
          "Invalid output channel config (mode=" + outputMode + ") for: " + inputFormat);
    }

    offloadDisabledUntilNextConfiguration = false;
    Configuration pendingConfiguration =
        new Configuration(
            inputFormat,
            inputPcmFrameSize,
            outputMode,
            outputPcmFrameSize,
            outputSampleRate,
            outputChannelConfig,
            outputEncoding,
            specifiedBufferSize,
            enableAudioTrackPlaybackParams,
            canApplyPlaybackParameters,
            availableAudioProcessors);
    if (isAudioTrackInitialized()) {
      this.pendingConfiguration = pendingConfiguration;
    } else {
      configuration = pendingConfiguration;
    }
  }

  private void setupAudioProcessors() {
    AudioProcessor[] audioProcessors = configuration.availableAudioProcessors;
    ArrayList<AudioProcessor> newAudioProcessors = new ArrayList<>();
    for (AudioProcessor audioProcessor : audioProcessors) {
      if (audioProcessor.isActive()) {
        newAudioProcessors.add(audioProcessor);
      } else {
        audioProcessor.flush();
      }
    }
    int count = newAudioProcessors.size();
    activeAudioProcessors = newAudioProcessors.toArray(new AudioProcessor[count]);
    outputBuffers = new ByteBuffer[count];
    flushAudioProcessors();
  }

  private void flushAudioProcessors() {
    for (int i = 0; i < activeAudioProcessors.length; i++) {
      AudioProcessor audioProcessor = activeAudioProcessors[i];
      audioProcessor.flush();
      outputBuffers[i] = audioProcessor.getOutput();
    }
  }

  private void initializeAudioTrack() throws InitializationException {
    // If we're asynchronously releasing a previous audio track then we block until it has been
    // released. This guarantees that we cannot end up in a state where we have multiple audio
    // track instances. Without this guarantee it would be possible, in extreme cases, to exhaust
    // the shared memory that's available for audio track buffers. This would in turn cause the
    // initialization of the audio track to fail.
    releasingConditionVariable.block();

    audioTrack = buildAudioTrack();
    if (isOffloadedPlayback(audioTrack)) {
      registerStreamEventCallbackV29(audioTrack);
      audioTrack.setOffloadDelayPadding(
          configuration.inputFormat.encoderDelay, configuration.inputFormat.encoderPadding);
    }
    int audioSessionId = audioTrack.getAudioSessionId();
    if (enablePreV21AudioSessionWorkaround) {
      if (Util.SDK_INT < 21) {
        // The workaround creates an audio track with a two byte buffer on the same session, and
        // does not release it until this object is released, which keeps the session active.
        if (keepSessionIdAudioTrack != null
            && audioSessionId != keepSessionIdAudioTrack.getAudioSessionId()) {
          releaseKeepSessionIdAudioTrack();
        }
        if (keepSessionIdAudioTrack == null) {
          keepSessionIdAudioTrack = initializeKeepSessionIdAudioTrack(audioSessionId);
        }
      }
    }
    if (this.audioSessionId != audioSessionId) {
      this.audioSessionId = audioSessionId;
      if (listener != null) {
        listener.onAudioSessionId(audioSessionId);
      }
    }

    audioTrackPositionTracker.setAudioTrack(
        audioTrack,
        /* isPassthrough= */ configuration.outputMode == OUTPUT_MODE_PASSTHROUGH,
        configuration.outputEncoding,
        configuration.outputPcmFrameSize,
        configuration.bufferSize);
    setVolumeInternal();

    if (auxEffectInfo.effectId != AuxEffectInfo.NO_AUX_EFFECT_ID) {
      audioTrack.attachAuxEffect(auxEffectInfo.effectId);
      audioTrack.setAuxEffectSendLevel(auxEffectInfo.sendLevel);
    }

    startMediaTimeUsNeedsInit = true;
  }

  @Override
  public void play() {
    playing = true;
    if (isAudioTrackInitialized()) {
      audioTrackPositionTracker.start();
      audioTrack.play();
    }
  }

  @Override
  public void handleDiscontinuity() {
    // Force resynchronization after a skipped buffer.
    startMediaTimeUsNeedsSync = true;
  }

  @Override
  @SuppressWarnings("ReferenceEquality")
  public boolean handleBuffer(
      ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount)
      throws InitializationException, WriteException {
    Assertions.checkArgument(inputBuffer == null || buffer == inputBuffer);

    if (pendingConfiguration != null) {
      if (!drainToEndOfStream()) {
        // There's still pending data in audio processors to write to the track.
        return false;
      } else if (!pendingConfiguration.canReuseAudioTrack(configuration)) {
        playPendingData();
        if (hasPendingData()) {
          // We're waiting for playout on the current audio track to finish.
          return false;
        }
        flush();
      } else {
        // The current audio track can be reused for the new configuration.
        configuration = pendingConfiguration;
        pendingConfiguration = null;
        if (isOffloadedPlayback(audioTrack)) {
          audioTrack.setOffloadEndOfStream();
          audioTrack.setOffloadDelayPadding(
              configuration.inputFormat.encoderDelay, configuration.inputFormat.encoderPadding);
          isWaitingForOffloadEndOfStreamHandled = true;
        }
      }
      // Re-apply playback parameters.
      applyAudioProcessorPlaybackParametersAndSkipSilence(presentationTimeUs);
    }

    if (!isAudioTrackInitialized()) {
      initializeAudioTrack();
    }

    if (startMediaTimeUsNeedsInit) {
      startMediaTimeUs = max(0, presentationTimeUs);
      startMediaTimeUsNeedsSync = false;
      startMediaTimeUsNeedsInit = false;

      if (enableAudioTrackPlaybackParams && Util.SDK_INT >= 23) {
        setAudioTrackPlaybackParametersV23(audioTrackPlaybackParameters);
      }
      applyAudioProcessorPlaybackParametersAndSkipSilence(presentationTimeUs);

      if (playing) {
        play();
      }
    }

    if (!audioTrackPositionTracker.mayHandleBuffer(getWrittenFrames())) {
      return false;
    }

    if (inputBuffer == null) {
      // We are seeing this buffer for the first time.
      Assertions.checkArgument(buffer.order() == ByteOrder.LITTLE_ENDIAN);
      if (!buffer.hasRemaining()) {
        // The buffer is empty.
        return true;
      }

      if (configuration.outputMode != OUTPUT_MODE_PCM && framesPerEncodedSample == 0) {
        // If this is the first encoded sample, calculate the sample size in frames.
        framesPerEncodedSample = getFramesPerEncodedSample(configuration.outputEncoding, buffer);
        if (framesPerEncodedSample == 0) {
          // We still don't know the number of frames per sample, so drop the buffer.
          // For TrueHD this can occur after some seek operations, as not every sample starts with
          // a syncframe header. If we chunked samples together so the extracted samples always
          // started with a syncframe header, the chunks would be too large.
          return true;
        }
      }

      if (afterDrainParameters != null) {
        if (!drainToEndOfStream()) {
          // Don't process any more input until draining completes.
          return false;
        }
        applyAudioProcessorPlaybackParametersAndSkipSilence(presentationTimeUs);
        afterDrainParameters = null;
      }

      // Check that presentationTimeUs is consistent with the expected value.
      long expectedPresentationTimeUs =
          startMediaTimeUs
              + configuration.inputFramesToDurationUs(
                  getSubmittedFrames() - trimmingAudioProcessor.getTrimmedFrameCount());
      if (!startMediaTimeUsNeedsSync
          && Math.abs(expectedPresentationTimeUs - presentationTimeUs) > 200000) {
        Log.e(
            TAG,
            "Discontinuity detected [expected "
                + expectedPresentationTimeUs
                + ", got "
                + presentationTimeUs
                + "]");
        startMediaTimeUsNeedsSync = true;
      }
      if (startMediaTimeUsNeedsSync) {
        if (!drainToEndOfStream()) {
          // Don't update timing until pending AudioProcessor buffers are completely drained.
          return false;
        }
        // Adjust startMediaTimeUs to be consistent with the current buffer's start time and the
        // number of bytes submitted.
        long adjustmentUs = presentationTimeUs - expectedPresentationTimeUs;
        startMediaTimeUs += adjustmentUs;
        startMediaTimeUsNeedsSync = false;
        // Re-apply playback parameters because the startMediaTimeUs changed.
        applyAudioProcessorPlaybackParametersAndSkipSilence(presentationTimeUs);
        if (listener != null && adjustmentUs != 0) {
          listener.onPositionDiscontinuity();
        }
      }

      if (configuration.outputMode == OUTPUT_MODE_PCM) {
        submittedPcmBytes += buffer.remaining();
      } else {
        submittedEncodedFrames += framesPerEncodedSample * encodedAccessUnitCount;
      }

      inputBuffer = buffer;
      inputBufferAccessUnitCount = encodedAccessUnitCount;
    }

    processBuffers(presentationTimeUs);

    if (!inputBuffer.hasRemaining()) {
      inputBuffer = null;
      inputBufferAccessUnitCount = 0;
      return true;
    }

    if (audioTrackPositionTracker.isStalled(getWrittenFrames())) {
      Log.w(TAG, "Resetting stalled audio track");
      flush();
      return true;
    }

    return false;
  }

  private AudioTrack buildAudioTrack() throws InitializationException {
    try {
      return Assertions.checkNotNull(configuration)
          .buildAudioTrack(tunneling, audioAttributes, audioSessionId);
    } catch (InitializationException e) {
      maybeDisableOffload();
      throw e;
    }
  }

  @RequiresApi(29)
  private void registerStreamEventCallbackV29(AudioTrack audioTrack) {
    if (offloadStreamEventCallbackV29 == null) {
      // Must be lazily initialized to receive stream event callbacks on the current (playback)
      // thread as the constructor is not called in the playback thread.
      offloadStreamEventCallbackV29 = new StreamEventCallbackV29();
    }
    offloadStreamEventCallbackV29.register(audioTrack);
  }

  private void processBuffers(long avSyncPresentationTimeUs) throws WriteException {
    int count = activeAudioProcessors.length;
    int index = count;
    while (index >= 0) {
      ByteBuffer input = index > 0 ? outputBuffers[index - 1]
          : (inputBuffer != null ? inputBuffer : AudioProcessor.EMPTY_BUFFER);
      if (index == count) {
        writeBuffer(input, avSyncPresentationTimeUs);
      } else {
        AudioProcessor audioProcessor = activeAudioProcessors[index];
        audioProcessor.queueInput(input);
        ByteBuffer output = audioProcessor.getOutput();
        outputBuffers[index] = output;
        if (output.hasRemaining()) {
          // Handle the output as input to the next audio processor or the AudioTrack.
          index++;
          continue;
        }
      }

      if (input.hasRemaining()) {
        // The input wasn't consumed and no output was produced, so give up for now.
        return;
      }

      // Get more input from upstream.
      index--;
    }
  }

  @SuppressWarnings("ReferenceEquality")
  private void writeBuffer(ByteBuffer buffer, long avSyncPresentationTimeUs) throws WriteException {
    if (!buffer.hasRemaining()) {
      return;
    }
    if (outputBuffer != null) {
      Assertions.checkArgument(outputBuffer == buffer);
    } else {
      outputBuffer = buffer;
      if (Util.SDK_INT < 21) {
        int bytesRemaining = buffer.remaining();
        if (preV21OutputBuffer == null || preV21OutputBuffer.length < bytesRemaining) {
          preV21OutputBuffer = new byte[bytesRemaining];
        }
        int originalPosition = buffer.position();
        buffer.get(preV21OutputBuffer, 0, bytesRemaining);
        buffer.position(originalPosition);
        preV21OutputBufferOffset = 0;
      }
    }
    int bytesRemaining = buffer.remaining();
    int bytesWritten = 0;
    if (Util.SDK_INT < 21) { // outputMode == OUTPUT_MODE_PCM.
      // Work out how many bytes we can write without the risk of blocking.
      int bytesToWrite = audioTrackPositionTracker.getAvailableBufferSize(writtenPcmBytes);
      if (bytesToWrite > 0) {
        bytesToWrite = min(bytesRemaining, bytesToWrite);
        bytesWritten = audioTrack.write(preV21OutputBuffer, preV21OutputBufferOffset, bytesToWrite);
        if (bytesWritten > 0) {
          preV21OutputBufferOffset += bytesWritten;
          buffer.position(buffer.position() + bytesWritten);
        }
      }
    } else if (tunneling) {
      Assertions.checkState(avSyncPresentationTimeUs != C.TIME_UNSET);
      bytesWritten =
          writeNonBlockingWithAvSyncV21(
              audioTrack, buffer, bytesRemaining, avSyncPresentationTimeUs);
    } else {
      bytesWritten = writeNonBlockingV21(audioTrack, buffer, bytesRemaining);
    }

    lastFeedElapsedRealtimeMs = SystemClock.elapsedRealtime();

    if (bytesWritten < 0) {
      boolean isRecoverable = isAudioTrackDeadObject(bytesWritten);
      if (isRecoverable) {
        maybeDisableOffload();
      }
      throw new WriteException(bytesWritten);
    }

    if (isOffloadedPlayback(audioTrack)) {
      // After calling AudioTrack.setOffloadEndOfStream, the AudioTrack internally stops and
      // restarts during which AudioTrack.write will return 0. This situation must be detected to
      // prevent reporting the buffer as full even though it is not which could lead ExoPlayer to
      // sleep forever waiting for a onDataRequest that will never come.
      if (writtenEncodedFrames > 0) {
        isWaitingForOffloadEndOfStreamHandled = false;
      }

      // Consider the offload buffer as full if the AudioTrack is playing and AudioTrack.write could
      // not write all the data provided to it. This relies on the assumption that AudioTrack.write
      // always writes as much as possible.
      if (playing
          && listener != null
          && bytesWritten < bytesRemaining
          && !isWaitingForOffloadEndOfStreamHandled) {
        long pendingDurationMs =
            audioTrackPositionTracker.getPendingBufferDurationMs(writtenEncodedFrames);
        listener.onOffloadBufferFull(pendingDurationMs);
      }
    }

    if (configuration.outputMode == OUTPUT_MODE_PCM) {
      writtenPcmBytes += bytesWritten;
    }
    if (bytesWritten == bytesRemaining) {
      if (configuration.outputMode != OUTPUT_MODE_PCM) {
        // When playing non-PCM, the inputBuffer is never processed, thus the last inputBuffer
        // must be the current input buffer.
        Assertions.checkState(buffer == inputBuffer);
        writtenEncodedFrames += framesPerEncodedSample * inputBufferAccessUnitCount;
      }
      outputBuffer = null;
    }
  }

  @Override
  public void playToEndOfStream() throws WriteException {
    if (!handledEndOfStream && isAudioTrackInitialized() && drainToEndOfStream()) {
      playPendingData();
      handledEndOfStream = true;
    }
  }

  private void maybeDisableOffload() {
    if (!configuration.outputModeIsOffload()) {
      return;
    }
    // Offload was requested, but may not be available. There are cases when this can occur even if
    // AudioManager.isOffloadedPlaybackSupported returned true. For example, due to use of an
    // AudioPlaybackCaptureConfiguration. Disable offload until the sink is next configured.
    offloadDisabledUntilNextConfiguration = true;
  }

  private static boolean isAudioTrackDeadObject(int status) {
    return Util.SDK_INT >= 24 && status == AudioTrack.ERROR_DEAD_OBJECT;
  }

  private boolean drainToEndOfStream() throws WriteException {
    boolean audioProcessorNeedsEndOfStream = false;
    if (drainingAudioProcessorIndex == C.INDEX_UNSET) {
      drainingAudioProcessorIndex = 0;
      audioProcessorNeedsEndOfStream = true;
    }
    while (drainingAudioProcessorIndex < activeAudioProcessors.length) {
      AudioProcessor audioProcessor = activeAudioProcessors[drainingAudioProcessorIndex];
      if (audioProcessorNeedsEndOfStream) {
        audioProcessor.queueEndOfStream();
      }
      processBuffers(C.TIME_UNSET);
      if (!audioProcessor.isEnded()) {
        return false;
      }
      audioProcessorNeedsEndOfStream = true;
      drainingAudioProcessorIndex++;
    }

    // Finish writing any remaining output to the track.
    if (outputBuffer != null) {
      writeBuffer(outputBuffer, C.TIME_UNSET);
      if (outputBuffer != null) {
        return false;
      }
    }
    drainingAudioProcessorIndex = C.INDEX_UNSET;
    return true;
  }

  @Override
  public boolean isEnded() {
    return !isAudioTrackInitialized() || (handledEndOfStream && !hasPendingData());
  }

  @Override
  public boolean hasPendingData() {
    return isAudioTrackInitialized()
        && audioTrackPositionTracker.hasPendingData(getWrittenFrames());
  }

  @Override
  public void setPlaybackParameters(PlaybackParameters playbackParameters) {
    playbackParameters =
        new PlaybackParameters(
            Util.constrainValue(playbackParameters.speed, MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED),
            Util.constrainValue(playbackParameters.pitch, MIN_PITCH, MAX_PITCH));
    if (enableAudioTrackPlaybackParams && Util.SDK_INT >= 23) {
      setAudioTrackPlaybackParametersV23(playbackParameters);
    } else {
      setAudioProcessorPlaybackParametersAndSkipSilence(
          playbackParameters, getSkipSilenceEnabled());
    }
  }

  @Override
  public PlaybackParameters getPlaybackParameters() {
    return enableAudioTrackPlaybackParams
        ? audioTrackPlaybackParameters
        : getAudioProcessorPlaybackParameters();
  }

  @Override
  public void setSkipSilenceEnabled(boolean skipSilenceEnabled) {
    setAudioProcessorPlaybackParametersAndSkipSilence(
        getAudioProcessorPlaybackParameters(), skipSilenceEnabled);
  }

  @Override
  public boolean getSkipSilenceEnabled() {
    return getMediaPositionParameters().skipSilence;
  }

  @Override
  public void setAudioAttributes(AudioAttributes audioAttributes) {
    if (this.audioAttributes.equals(audioAttributes)) {
      return;
    }
    this.audioAttributes = audioAttributes;
    if (tunneling) {
      // The audio attributes are ignored in tunneling mode, so no need to reset.
      return;
    }
    flush();
    audioSessionId = C.AUDIO_SESSION_ID_UNSET;
  }

  @Override
  public void setAudioSessionId(int audioSessionId) {
    if (this.audioSessionId != audioSessionId) {
      this.audioSessionId = audioSessionId;
      flush();
    }
  }

  @Override
  public void setAuxEffectInfo(AuxEffectInfo auxEffectInfo) {
    if (this.auxEffectInfo.equals(auxEffectInfo)) {
      return;
    }
    int effectId = auxEffectInfo.effectId;
    float sendLevel = auxEffectInfo.sendLevel;
    if (audioTrack != null) {
      if (this.auxEffectInfo.effectId != effectId) {
        audioTrack.attachAuxEffect(effectId);
      }
      if (effectId != AuxEffectInfo.NO_AUX_EFFECT_ID) {
        audioTrack.setAuxEffectSendLevel(sendLevel);
      }
    }
    this.auxEffectInfo = auxEffectInfo;
  }

  @Override
  public void enableTunnelingV21(int tunnelingAudioSessionId) {
    Assertions.checkState(Util.SDK_INT >= 21);
    if (!tunneling || audioSessionId != tunnelingAudioSessionId) {
      tunneling = true;
      audioSessionId = tunnelingAudioSessionId;
      flush();
    }
  }

  @Override
  public void disableTunneling() {
    if (tunneling) {
      tunneling = false;
      audioSessionId = C.AUDIO_SESSION_ID_UNSET;
      flush();
    }
  }

  @Override
  public void setVolume(float volume) {
    if (this.volume != volume) {
      this.volume = volume;
      setVolumeInternal();
    }
  }

  private void setVolumeInternal() {
    if (!isAudioTrackInitialized()) {
      // Do nothing.
    } else if (Util.SDK_INT >= 21) {
      setVolumeInternalV21(audioTrack, volume);
    } else {
      setVolumeInternalV3(audioTrack, volume);
    }
  }

  @Override
  public void pause() {
    playing = false;
    if (isAudioTrackInitialized() && audioTrackPositionTracker.pause()) {
      audioTrack.pause();
    }
  }

  @Override
  public void flush() {
    if (isAudioTrackInitialized()) {
      resetSinkStateForFlush();

      if (audioTrackPositionTracker.isPlaying()) {
        audioTrack.pause();
      }
      if (isOffloadedPlayback(audioTrack)) {
        Assertions.checkNotNull(offloadStreamEventCallbackV29).unregister(audioTrack);
      }
      // AudioTrack.release can take some time, so we call it on a background thread.
      final AudioTrack toRelease = audioTrack;
      audioTrack = null;
      if (pendingConfiguration != null) {
        configuration = pendingConfiguration;
        pendingConfiguration = null;
      }
      audioTrackPositionTracker.reset();
      releasingConditionVariable.close();
      new Thread("ExoPlayer:AudioTrackReleaseThread") {
        @Override
        public void run() {
          try {
            toRelease.flush();
            toRelease.release();
          } finally {
            releasingConditionVariable.open();
          }
        }
      }.start();
    }
  }

  @Override
  public void experimentalFlushWithoutAudioTrackRelease() {
    // Prior to SDK 25, AudioTrack flush does not work as intended, and therefore it must be
    // released and reinitialized. (Internal reference: b/143500232)
    if (Util.SDK_INT < 25) {
      flush();
      return;
    }

    if (!isAudioTrackInitialized()) {
      return;
    }

    resetSinkStateForFlush();
    if (audioTrackPositionTracker.isPlaying()) {
      audioTrack.pause();
    }
    audioTrack.flush();

    audioTrackPositionTracker.reset();
    audioTrackPositionTracker.setAudioTrack(
        audioTrack,
        /* isPassthrough= */ configuration.outputMode == OUTPUT_MODE_PASSTHROUGH,
        configuration.outputEncoding,
        configuration.outputPcmFrameSize,
        configuration.bufferSize);

    startMediaTimeUsNeedsInit = true;
  }

  @Override
  public void reset() {
    flush();
    releaseKeepSessionIdAudioTrack();
    for (AudioProcessor audioProcessor : toIntPcmAvailableAudioProcessors) {
      audioProcessor.reset();
    }
    for (AudioProcessor audioProcessor : toFloatPcmAvailableAudioProcessors) {
      audioProcessor.reset();
    }
    audioSessionId = C.AUDIO_SESSION_ID_UNSET;
    playing = false;
    offloadDisabledUntilNextConfiguration = false;
  }

  // Internal methods.

  private void resetSinkStateForFlush() {
    submittedPcmBytes = 0;
    submittedEncodedFrames = 0;
    writtenPcmBytes = 0;
    writtenEncodedFrames = 0;
    isWaitingForOffloadEndOfStreamHandled = false;
    framesPerEncodedSample = 0;
    mediaPositionParameters =
        new MediaPositionParameters(
            getAudioProcessorPlaybackParameters(),
            getSkipSilenceEnabled(),
            /* mediaTimeUs= */ 0,
            /* audioTrackPositionUs= */ 0);
    startMediaTimeUs = 0;
    afterDrainParameters = null;
    mediaPositionParametersCheckpoints.clear();
    inputBuffer = null;
    inputBufferAccessUnitCount = 0;
    outputBuffer = null;
    stoppedAudioTrack = false;
    handledEndOfStream = false;
    drainingAudioProcessorIndex = C.INDEX_UNSET;
    avSyncHeader = null;
    bytesUntilNextAvSync = 0;
    trimmingAudioProcessor.resetTrimmedFrameCount();
    flushAudioProcessors();
  }

  /** Releases {@link #keepSessionIdAudioTrack} asynchronously, if it is non-{@code null}. */
  private void releaseKeepSessionIdAudioTrack() {
    if (keepSessionIdAudioTrack == null) {
      return;
    }

    // AudioTrack.release can take some time, so we call it on a background thread.
    final AudioTrack toRelease = keepSessionIdAudioTrack;
    keepSessionIdAudioTrack = null;
    new Thread() {
      @Override
      public void run() {
        toRelease.release();
      }
    }.start();
  }

  @RequiresApi(23)
  private void setAudioTrackPlaybackParametersV23(PlaybackParameters audioTrackPlaybackParameters) {
    if (isAudioTrackInitialized()) {
      PlaybackParams playbackParams =
          new PlaybackParams()
              .allowDefaults()
              .setSpeed(audioTrackPlaybackParameters.speed)
              .setPitch(audioTrackPlaybackParameters.pitch)
              .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL);
      try {
        audioTrack.setPlaybackParams(playbackParams);
      } catch (IllegalArgumentException e) {
        Log.w(TAG, "Failed to set playback params", e);
      }
      // Update the speed using the actual effective speed from the audio track.
      audioTrackPlaybackParameters =
          new PlaybackParameters(
              audioTrack.getPlaybackParams().getSpeed(), audioTrack.getPlaybackParams().getPitch());
      audioTrackPositionTracker.setAudioTrackPlaybackSpeed(audioTrackPlaybackParameters.speed);
    }
    this.audioTrackPlaybackParameters = audioTrackPlaybackParameters;
  }

  private void setAudioProcessorPlaybackParametersAndSkipSilence(
      PlaybackParameters playbackParameters, boolean skipSilence) {
    MediaPositionParameters currentMediaPositionParameters = getMediaPositionParameters();
    if (!playbackParameters.equals(currentMediaPositionParameters.playbackParameters)
        || skipSilence != currentMediaPositionParameters.skipSilence) {
      MediaPositionParameters mediaPositionParameters =
          new MediaPositionParameters(
              playbackParameters,
              skipSilence,
              /* mediaTimeUs= */ C.TIME_UNSET,
              /* audioTrackPositionUs= */ C.TIME_UNSET);
      if (isAudioTrackInitialized()) {
        // Drain the audio processors so we can determine the frame position at which the new
        // parameters apply.
        this.afterDrainParameters = mediaPositionParameters;
      } else {
        // Update the audio processor chain parameters now. They will be applied to the audio
        // processors during initialization.
        this.mediaPositionParameters = mediaPositionParameters;
      }
    }
  }

  private PlaybackParameters getAudioProcessorPlaybackParameters() {
    return getMediaPositionParameters().playbackParameters;
  }

  private MediaPositionParameters getMediaPositionParameters() {
    // Mask the already set parameters.
    return afterDrainParameters != null
        ? afterDrainParameters
        : !mediaPositionParametersCheckpoints.isEmpty()
            ? mediaPositionParametersCheckpoints.getLast()
            : mediaPositionParameters;
  }

  private void applyAudioProcessorPlaybackParametersAndSkipSilence(long presentationTimeUs) {
    PlaybackParameters playbackParameters =
        configuration.canApplyPlaybackParameters
            ? audioProcessorChain.applyPlaybackParameters(getAudioProcessorPlaybackParameters())
            : PlaybackParameters.DEFAULT;
    boolean skipSilenceEnabled =
        configuration.canApplyPlaybackParameters
            ? audioProcessorChain.applySkipSilenceEnabled(getSkipSilenceEnabled())
            : DEFAULT_SKIP_SILENCE;
    mediaPositionParametersCheckpoints.add(
        new MediaPositionParameters(
            playbackParameters,
            skipSilenceEnabled,
            /* mediaTimeUs= */ max(0, presentationTimeUs),
            /* audioTrackPositionUs= */ configuration.framesToDurationUs(getWrittenFrames())));
    setupAudioProcessors();
    if (listener != null) {
      listener.onSkipSilenceEnabledChanged(skipSilenceEnabled);
    }
  }

  /**
   * Applies and updates media position parameters.
   *
   * @param positionUs The current audio track position, in microseconds.
   * @return The current media time, in microseconds.
   */
  private long applyMediaPositionParameters(long positionUs) {
    while (!mediaPositionParametersCheckpoints.isEmpty()
        && positionUs >= mediaPositionParametersCheckpoints.getFirst().audioTrackPositionUs) {
      // We are playing (or about to play) media with the new parameters, so update them.
      mediaPositionParameters = mediaPositionParametersCheckpoints.remove();
    }

    long playoutDurationSinceLastCheckpointUs =
        positionUs - mediaPositionParameters.audioTrackPositionUs;
    if (mediaPositionParameters.playbackParameters.equals(PlaybackParameters.DEFAULT)) {
      return mediaPositionParameters.mediaTimeUs + playoutDurationSinceLastCheckpointUs;
    } else if (mediaPositionParametersCheckpoints.isEmpty()) {
      long mediaDurationSinceLastCheckpointUs =
          audioProcessorChain.getMediaDuration(playoutDurationSinceLastCheckpointUs);
      return mediaPositionParameters.mediaTimeUs + mediaDurationSinceLastCheckpointUs;
    } else {
      // The processor chain has been configured with new parameters, but we're still playing audio
      // that was processed using previous parameters. We can't scale the playout duration using the
      // processor chain in this case, so we fall back to scaling using the previous parameters'
      // target speed instead. Since the processor chain may not have achieved the target speed
      // precisely, we scale the duration to the next checkpoint (which will always be small) rather
      // than the duration from the previous checkpoint (which may be arbitrarily large). This
      // limits the amount of error that can be introduced due to a difference between the target
      // and actual speeds.
      MediaPositionParameters nextMediaPositionParameters =
          mediaPositionParametersCheckpoints.getFirst();
      long playoutDurationUntilNextCheckpointUs =
          nextMediaPositionParameters.audioTrackPositionUs - positionUs;
      long mediaDurationUntilNextCheckpointUs =
          Util.getMediaDurationForPlayoutDuration(
              playoutDurationUntilNextCheckpointUs,
              mediaPositionParameters.playbackParameters.speed);
      return nextMediaPositionParameters.mediaTimeUs - mediaDurationUntilNextCheckpointUs;
    }
  }

  private long applySkipping(long positionUs) {
    return positionUs
        + configuration.framesToDurationUs(audioProcessorChain.getSkippedOutputFrameCount());
  }

  private boolean isAudioTrackInitialized() {
    return audioTrack != null;
  }

  private long getSubmittedFrames() {
    return configuration.outputMode == OUTPUT_MODE_PCM
        ? (submittedPcmBytes / configuration.inputPcmFrameSize)
        : submittedEncodedFrames;
  }

  private long getWrittenFrames() {
    return configuration.outputMode == OUTPUT_MODE_PCM
        ? (writtenPcmBytes / configuration.outputPcmFrameSize)
        : writtenEncodedFrames;
  }

  private static boolean isPassthroughPlaybackSupported(
      Format format, @Nullable AudioCapabilities audioCapabilities) {
    return getEncodingAndChannelConfigForPassthrough(format, audioCapabilities) != null;
  }

  /**
   * Returns the encoding and channel config to use when configuring an {@link AudioTrack} in
   * passthrough mode for the specified {@link Format}. Returns {@code null} if passthrough of the
   * format is unsupported.
   *
   * @param format The {@link Format}.
   * @param audioCapabilities The device audio capabilities.
   * @return The encoding and channel config to use, or {@code null} if passthrough of the format is
   *     unsupported.
   */
  @Nullable
  private static Pair<Integer, Integer> getEncodingAndChannelConfigForPassthrough(
      Format format, @Nullable AudioCapabilities audioCapabilities) {
    if (audioCapabilities == null) {
      return null;
    }

    @C.Encoding
    int encoding =
        MimeTypes.getEncoding(Assertions.checkNotNull(format.sampleMimeType), format.codecs);
    // Check for encodings that are known to work for passthrough with the implementation in this
    // class. This avoids trying to use passthrough with an encoding where the device/app reports
    // it's capable but it is untested or known to be broken (for example AAC-LC).
    boolean supportedEncoding =
        encoding == C.ENCODING_AC3
            || encoding == C.ENCODING_E_AC3
            || encoding == C.ENCODING_E_AC3_JOC
            || encoding == C.ENCODING_AC4
            || encoding == C.ENCODING_DTS
            || encoding == C.ENCODING_DTS_HD
            || encoding == C.ENCODING_DOLBY_TRUEHD;
    if (!supportedEncoding) {
      return null;
    }

    // E-AC3 JOC is object based, so any channel count specified in the format is arbitrary. Use 6,
    // since the E-AC3 compatible part of the stream is 5.1.
    int channelCount = encoding == C.ENCODING_E_AC3_JOC ? 6 : format.channelCount;
    if (channelCount > audioCapabilities.getMaxChannelCount()) {
      return null;
    }

    int channelConfig = getChannelConfigForPassthrough(channelCount);
    if (channelConfig == AudioFormat.CHANNEL_INVALID) {
      return null;
    }

    if (audioCapabilities.supportsEncoding(encoding)) {
      return Pair.create(encoding, channelConfig);
    } else if (encoding == C.ENCODING_E_AC3_JOC
        && audioCapabilities.supportsEncoding(C.ENCODING_E_AC3)) {
      // E-AC3 receivers support E-AC3 JOC streams (but decode in 2-D rather than 3-D).
      return Pair.create(C.ENCODING_E_AC3, channelConfig);
    }

    return null;
  }

  private static int getChannelConfigForPassthrough(int channelCount) {
    if (Util.SDK_INT <= 28) {
      // In passthrough mode the channel count used to configure the audio track doesn't affect how
      // the stream is handled, except that some devices do overly-strict channel configuration
      // checks. Therefore we override the channel count so that a known-working channel
      // configuration is chosen in all cases. See [Internal: b/29116190].
      if (channelCount == 7) {
        channelCount = 8;
      } else if (channelCount == 3 || channelCount == 4 || channelCount == 5) {
        channelCount = 6;
      }
    }

    // Workaround for Nexus Player not reporting support for mono passthrough. See
    // [Internal: b/34268671].
    if (Util.SDK_INT <= 26 && "fugu".equals(Util.DEVICE) && channelCount == 1) {
      channelCount = 2;
    }

    return Util.getAudioTrackChannelConfig(channelCount);
  }

  private static boolean isOffloadedPlaybackSupported(
      Format format, AudioAttributes audioAttributes) {
    if (Util.SDK_INT < 29) {
      return false;
    }
    @C.Encoding
    int encoding =
        MimeTypes.getEncoding(Assertions.checkNotNull(format.sampleMimeType), format.codecs);
    if (encoding == C.ENCODING_INVALID) {
      return false;
    }
    int channelConfig = Util.getAudioTrackChannelConfig(format.channelCount);
    if (channelConfig == AudioFormat.CHANNEL_INVALID) {
      return false;
    }
    AudioFormat audioFormat = getAudioFormat(format.sampleRate, channelConfig, encoding);
    if (!AudioManager.isOffloadedPlaybackSupported(
        audioFormat, audioAttributes.getAudioAttributesV21())) {
      return false;
    }
    boolean notGapless = format.encoderDelay == 0 && format.encoderPadding == 0;
    return notGapless || isOffloadedGaplessPlaybackSupported();
  }

  private static boolean isOffloadedPlayback(AudioTrack audioTrack) {
    return Util.SDK_INT >= 29 && audioTrack.isOffloadedPlayback();
  }

  /**
   * Returns whether the device supports gapless in offload playback.
   *
   * <p>Gapless offload is not supported by all devices and there is no API to query its support. As
   * a result this detection is currently based on manual testing.
   */
  // TODO(internal b/158191844): Add an SDK API to query offload gapless support.
  private static boolean isOffloadedGaplessPlaybackSupported() {
    return Util.SDK_INT >= 30 && Util.MODEL.startsWith("Pixel");
  }

  private static AudioTrack initializeKeepSessionIdAudioTrack(int audioSessionId) {
    int sampleRate = 4000; // Equal to private AudioTrack.MIN_SAMPLE_RATE.
    int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
    @C.PcmEncoding int encoding = C.ENCODING_PCM_16BIT;
    int bufferSize = 2; // Use a two byte buffer, as it is not actually used for playback.
    return new AudioTrack(
        C.STREAM_TYPE_DEFAULT,
        sampleRate,
        channelConfig,
        encoding,
        bufferSize,
        MODE_STATIC,
        audioSessionId);
  }

  private static int getMaximumEncodedRateBytesPerSecond(@C.Encoding int encoding) {
    switch (encoding) {
      case C.ENCODING_MP3:
        return MpegAudioUtil.MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_AAC_LC:
        return AacUtil.AAC_LC_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_AAC_HE_V1:
        return AacUtil.AAC_HE_V1_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_AAC_HE_V2:
        return AacUtil.AAC_HE_V2_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_AAC_XHE:
        return AacUtil.AAC_XHE_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_AAC_ELD:
        return AacUtil.AAC_ELD_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_AC3:
        return Ac3Util.AC3_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_E_AC3:
      case C.ENCODING_E_AC3_JOC:
        return Ac3Util.E_AC3_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_AC4:
        return Ac4Util.MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_DTS:
        return DtsUtil.DTS_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_DTS_HD:
        return DtsUtil.DTS_HD_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_DOLBY_TRUEHD:
        return Ac3Util.TRUEHD_MAX_RATE_BYTES_PER_SECOND;
      case C.ENCODING_PCM_16BIT:
      case C.ENCODING_PCM_16BIT_BIG_ENDIAN:
      case C.ENCODING_PCM_24BIT:
      case C.ENCODING_PCM_32BIT:
      case C.ENCODING_PCM_8BIT:
      case C.ENCODING_PCM_FLOAT:
      case C.ENCODING_AAC_ER_BSAC:
      case C.ENCODING_INVALID:
      case Format.NO_VALUE:
      default:
        throw new IllegalArgumentException();
    }
  }

  private static int getFramesPerEncodedSample(@C.Encoding int encoding, ByteBuffer buffer) {
    switch (encoding) {
      case C.ENCODING_MP3:
        int headerDataInBigEndian = Util.getBigEndianInt(buffer, buffer.position());
        int frameCount = MpegAudioUtil.parseMpegAudioFrameSampleCount(headerDataInBigEndian);
        if (frameCount == C.LENGTH_UNSET) {
          throw new IllegalArgumentException();
        }
        return frameCount;
      case C.ENCODING_AAC_LC:
        return AacUtil.AAC_LC_AUDIO_SAMPLE_COUNT;
      case C.ENCODING_AAC_HE_V1:
      case C.ENCODING_AAC_HE_V2:
        return AacUtil.AAC_HE_AUDIO_SAMPLE_COUNT;
      case C.ENCODING_AAC_XHE:
        return AacUtil.AAC_XHE_AUDIO_SAMPLE_COUNT;
      case C.ENCODING_AAC_ELD:
        return AacUtil.AAC_LD_AUDIO_SAMPLE_COUNT;
      case C.ENCODING_DTS:
      case C.ENCODING_DTS_HD:
        return DtsUtil.parseDtsAudioSampleCount(buffer);
      case C.ENCODING_AC3:
      case C.ENCODING_E_AC3:
      case C.ENCODING_E_AC3_JOC:
        return Ac3Util.parseAc3SyncframeAudioSampleCount(buffer);
      case C.ENCODING_AC4:
        return Ac4Util.parseAc4SyncframeAudioSampleCount(buffer);
      case C.ENCODING_DOLBY_TRUEHD:
        int syncframeOffset = Ac3Util.findTrueHdSyncframeOffset(buffer);
        return syncframeOffset == C.INDEX_UNSET
            ? 0
            : (Ac3Util.parseTrueHdSyncframeAudioSampleCount(buffer, syncframeOffset)
                * Ac3Util.TRUEHD_RECHUNK_SAMPLE_COUNT);
      case C.ENCODING_PCM_16BIT:
      case C.ENCODING_PCM_16BIT_BIG_ENDIAN:
      case C.ENCODING_PCM_24BIT:
      case C.ENCODING_PCM_32BIT:
      case C.ENCODING_PCM_8BIT:
      case C.ENCODING_PCM_FLOAT:
      case C.ENCODING_AAC_ER_BSAC:
      case C.ENCODING_INVALID:
      case Format.NO_VALUE:
      default:
        throw new IllegalStateException("Unexpected audio encoding: " + encoding);
    }
  }

  @RequiresApi(21)
  private static int writeNonBlockingV21(AudioTrack audioTrack, ByteBuffer buffer, int size) {
    return audioTrack.write(buffer, size, WRITE_NON_BLOCKING);
  }

  @RequiresApi(21)
  private int writeNonBlockingWithAvSyncV21(
      AudioTrack audioTrack, ByteBuffer buffer, int size, long presentationTimeUs) {
    if (Util.SDK_INT >= 26) {
      // The underlying platform AudioTrack writes AV sync headers directly.
      return audioTrack.write(buffer, size, WRITE_NON_BLOCKING, presentationTimeUs * 1000);
    }
    if (avSyncHeader == null) {
      avSyncHeader = ByteBuffer.allocate(16);
      avSyncHeader.order(ByteOrder.BIG_ENDIAN);
      avSyncHeader.putInt(0x55550001);
    }
    if (bytesUntilNextAvSync == 0) {
      avSyncHeader.putInt(4, size);
      avSyncHeader.putLong(8, presentationTimeUs * 1000);
      avSyncHeader.position(0);
      bytesUntilNextAvSync = size;
    }
    int avSyncHeaderBytesRemaining = avSyncHeader.remaining();
    if (avSyncHeaderBytesRemaining > 0) {
      int result = audioTrack.write(avSyncHeader, avSyncHeaderBytesRemaining, WRITE_NON_BLOCKING);
      if (result < 0) {
        bytesUntilNextAvSync = 0;
        return result;
      }
      if (result < avSyncHeaderBytesRemaining) {
        return 0;
      }
    }
    int result = writeNonBlockingV21(audioTrack, buffer, size);
    if (result < 0) {
      bytesUntilNextAvSync = 0;
      return result;
    }
    bytesUntilNextAvSync -= result;
    return result;
  }

  @RequiresApi(21)
  private static void setVolumeInternalV21(AudioTrack audioTrack, float volume) {
    audioTrack.setVolume(volume);
  }

  private static void setVolumeInternalV3(AudioTrack audioTrack, float volume) {
    audioTrack.setStereoVolume(volume, volume);
  }

  private void playPendingData() {
    if (!stoppedAudioTrack) {
      stoppedAudioTrack = true;
      audioTrackPositionTracker.handleEndOfStream(getWrittenFrames());
      audioTrack.stop();
      bytesUntilNextAvSync = 0;
    }
  }

  @RequiresApi(29)
  private final class StreamEventCallbackV29 {
    private final Handler handler;
    private final AudioTrack.StreamEventCallback callback;

    public StreamEventCallbackV29() {
      handler = new Handler();
      // Avoid StreamEventCallbackV29 inheriting directly from AudioTrack.StreamEventCallback as it
      // would cause a NoClassDefFoundError warning on load of DefaultAudioSink for SDK < 29.
      // See: https://github.com/google/ExoPlayer/issues/8058
      callback =
          new AudioTrack.StreamEventCallback() {
            @Override
            public void onDataRequest(AudioTrack track, int size) {
              Assertions.checkState(track == DefaultAudioSink.this.audioTrack);
              if (listener != null) {
                listener.onOffloadBufferEmptying();
              }
            }

            @Override
            public void onTearDown(@NonNull AudioTrack track) {
              if (listener != null && playing) {
                // A new Audio Track needs to be created and it's buffer filled, which will be done
                // on the next handleBuffer call. Request this call explicitly in case ExoPlayer is
                // sleeping waiting for a data request.
                listener.onOffloadBufferEmptying();
              }
            }
          };
    }

    public void register(AudioTrack audioTrack) {
      audioTrack.registerStreamEventCallback(handler::post, callback);
    }

    public void unregister(AudioTrack audioTrack) {
      audioTrack.unregisterStreamEventCallback(callback);
      handler.removeCallbacksAndMessages(/* token= */ null);
    }
  }

  /** Stores parameters used to calculate the current media position. */
  private static final class MediaPositionParameters {

    /** The playback parameters. */
    public final PlaybackParameters playbackParameters;
    /** Whether to skip silences. */
    public final boolean skipSilence;
    /** The media time from which the playback parameters apply, in microseconds. */
    public final long mediaTimeUs;
    /** The audio track position from which the playback parameters apply, in microseconds. */
    public final long audioTrackPositionUs;

    private MediaPositionParameters(
        PlaybackParameters playbackParameters,
        boolean skipSilence,
        long mediaTimeUs,
        long audioTrackPositionUs) {
      this.playbackParameters = playbackParameters;
      this.skipSilence = skipSilence;
      this.mediaTimeUs = mediaTimeUs;
      this.audioTrackPositionUs = audioTrackPositionUs;
    }
  }

  @RequiresApi(21)
  private static AudioFormat getAudioFormat(int sampleRate, int channelConfig, int encoding) {
    return new AudioFormat.Builder()
        .setSampleRate(sampleRate)
        .setChannelMask(channelConfig)
        .setEncoding(encoding)
        .build();
  }

  private final class PositionTrackerListener implements AudioTrackPositionTracker.Listener {

    @Override
    public void onPositionFramesMismatch(
        long audioTimestampPositionFrames,
        long audioTimestampSystemTimeUs,
        long systemTimeUs,
        long playbackPositionUs) {
      String message =
          "Spurious audio timestamp (frame position mismatch): "
              + audioTimestampPositionFrames
              + ", "
              + audioTimestampSystemTimeUs
              + ", "
              + systemTimeUs
              + ", "
              + playbackPositionUs
              + ", "
              + getSubmittedFrames()
              + ", "
              + getWrittenFrames();
      if (failOnSpuriousAudioTimestamp) {
        throw new InvalidAudioTrackTimestampException(message);
      }
      Log.w(TAG, message);
    }

    @Override
    public void onSystemTimeUsMismatch(
        long audioTimestampPositionFrames,
        long audioTimestampSystemTimeUs,
        long systemTimeUs,
        long playbackPositionUs) {
      String message =
          "Spurious audio timestamp (system clock mismatch): "
              + audioTimestampPositionFrames
              + ", "
              + audioTimestampSystemTimeUs
              + ", "
              + systemTimeUs
              + ", "
              + playbackPositionUs
              + ", "
              + getSubmittedFrames()
              + ", "
              + getWrittenFrames();
      if (failOnSpuriousAudioTimestamp) {
        throw new InvalidAudioTrackTimestampException(message);
      }
      Log.w(TAG, message);
    }

    @Override
    public void onInvalidLatency(long latencyUs) {
      Log.w(TAG, "Ignoring impossibly large audio latency: " + latencyUs);
    }

    @Override
    public void onPositionAdvancing(long playoutStartSystemTimeMs) {
      if (listener != null) {
        listener.onPositionAdvancing(playoutStartSystemTimeMs);
      }
    }

    @Override
    public void onUnderrun(int bufferSize, long bufferSizeMs) {
      if (listener != null) {
        long elapsedSinceLastFeedMs = SystemClock.elapsedRealtime() - lastFeedElapsedRealtimeMs;
        listener.onUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs);
      }
    }
  }

  /** Stores configuration relating to the audio format. */
  private static final class Configuration {

    public final Format inputFormat;
    public final int inputPcmFrameSize;
    @OutputMode public final int outputMode;
    public final int outputPcmFrameSize;
    public final int outputSampleRate;
    public final int outputChannelConfig;
    @C.Encoding public final int outputEncoding;
    public final int bufferSize;
    public final boolean canApplyPlaybackParameters;
    public final AudioProcessor[] availableAudioProcessors;

    public Configuration(
        Format inputFormat,
        int inputPcmFrameSize,
        @OutputMode int outputMode,
        int outputPcmFrameSize,
        int outputSampleRate,
        int outputChannelConfig,
        int outputEncoding,
        int specifiedBufferSize,
        boolean enableAudioTrackPlaybackParams,
        boolean canApplyPlaybackParameters,
        AudioProcessor[] availableAudioProcessors) {
      this.inputFormat = inputFormat;
      this.inputPcmFrameSize = inputPcmFrameSize;
      this.outputMode = outputMode;
      this.outputPcmFrameSize = outputPcmFrameSize;
      this.outputSampleRate = outputSampleRate;
      this.outputChannelConfig = outputChannelConfig;
      this.outputEncoding = outputEncoding;
      this.canApplyPlaybackParameters = canApplyPlaybackParameters;
      this.availableAudioProcessors = availableAudioProcessors;

      // Call computeBufferSize() last as it depends on the other configuration values.
      this.bufferSize = computeBufferSize(specifiedBufferSize, enableAudioTrackPlaybackParams);
    }

    /** Returns if the configurations are sufficiently compatible to reuse the audio track. */
    public boolean canReuseAudioTrack(Configuration audioTrackConfiguration) {
      return audioTrackConfiguration.outputMode == outputMode
          && audioTrackConfiguration.outputEncoding == outputEncoding
          && audioTrackConfiguration.outputSampleRate == outputSampleRate
          && audioTrackConfiguration.outputChannelConfig == outputChannelConfig
          && audioTrackConfiguration.outputPcmFrameSize == outputPcmFrameSize;
    }

    public long inputFramesToDurationUs(long frameCount) {
      return (frameCount * C.MICROS_PER_SECOND) / inputFormat.sampleRate;
    }

    public long framesToDurationUs(long frameCount) {
      return (frameCount * C.MICROS_PER_SECOND) / outputSampleRate;
    }

    public long durationUsToFrames(long durationUs) {
      return (durationUs * outputSampleRate) / C.MICROS_PER_SECOND;
    }

    public AudioTrack buildAudioTrack(
        boolean tunneling, AudioAttributes audioAttributes, int audioSessionId)
        throws InitializationException {
      AudioTrack audioTrack;
      try {
        audioTrack = createAudioTrack(tunneling, audioAttributes, audioSessionId);
      } catch (UnsupportedOperationException e) {
        throw new InitializationException(
            AudioTrack.STATE_UNINITIALIZED, outputSampleRate, outputChannelConfig, bufferSize);
      }

      int state = audioTrack.getState();
      if (state != STATE_INITIALIZED) {
        try {
          audioTrack.release();
        } catch (Exception e) {
          // The track has already failed to initialize, so it wouldn't be that surprising if
          // release were to fail too. Swallow the exception.
        }
        throw new InitializationException(state, outputSampleRate, outputChannelConfig, bufferSize);
      }
      return audioTrack;
    }

    private AudioTrack createAudioTrack(
        boolean tunneling, AudioAttributes audioAttributes, int audioSessionId) {
      if (Util.SDK_INT >= 29) {
        return createAudioTrackV29(tunneling, audioAttributes, audioSessionId);
      } else if (Util.SDK_INT >= 21) {
        return createAudioTrackV21(tunneling, audioAttributes, audioSessionId);
      } else {
        return createAudioTrackV9(audioAttributes, audioSessionId);
      }
    }

    @RequiresApi(29)
    private AudioTrack createAudioTrackV29(
        boolean tunneling, AudioAttributes audioAttributes, int audioSessionId) {
      AudioFormat audioFormat =
          getAudioFormat(outputSampleRate, outputChannelConfig, outputEncoding);
      android.media.AudioAttributes audioTrackAttributes =
          getAudioTrackAttributesV21(audioAttributes, tunneling);
      return new AudioTrack.Builder()
          .setAudioAttributes(audioTrackAttributes)
          .setAudioFormat(audioFormat)
          .setTransferMode(AudioTrack.MODE_STREAM)
          .setBufferSizeInBytes(bufferSize)
          .setSessionId(audioSessionId)
          .setOffloadedPlayback(outputMode == OUTPUT_MODE_OFFLOAD)
          .build();
    }

    @RequiresApi(21)
    private AudioTrack createAudioTrackV21(
        boolean tunneling, AudioAttributes audioAttributes, int audioSessionId) {
      return new AudioTrack(
          getAudioTrackAttributesV21(audioAttributes, tunneling),
          getAudioFormat(outputSampleRate, outputChannelConfig, outputEncoding),
          bufferSize,
          MODE_STREAM,
          audioSessionId);
    }

    private AudioTrack createAudioTrackV9(AudioAttributes audioAttributes, int audioSessionId) {
      int streamType = Util.getStreamTypeForAudioUsage(audioAttributes.usage);
      if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
        return new AudioTrack(
            streamType,
            outputSampleRate,
            outputChannelConfig,
            outputEncoding,
            bufferSize,
            MODE_STREAM);
      } else {
        // Re-attach to the same audio session.
        return new AudioTrack(
            streamType,
            outputSampleRate,
            outputChannelConfig,
            outputEncoding,
            bufferSize,
            MODE_STREAM,
            audioSessionId);
      }
    }

    private int computeBufferSize(
        int specifiedBufferSize, boolean enableAudioTrackPlaybackParameters) {
      if (specifiedBufferSize != 0) {
        return specifiedBufferSize;
      }
      switch (outputMode) {
        case OUTPUT_MODE_PCM:
          return getPcmDefaultBufferSize(
              enableAudioTrackPlaybackParameters ? MAX_PLAYBACK_SPEED : DEFAULT_PLAYBACK_SPEED);
        case OUTPUT_MODE_OFFLOAD:
          return getEncodedDefaultBufferSize(OFFLOAD_BUFFER_DURATION_US);
        case OUTPUT_MODE_PASSTHROUGH:
          return getEncodedDefaultBufferSize(PASSTHROUGH_BUFFER_DURATION_US);
        default:
          throw new IllegalStateException();
      }
    }

    private int getEncodedDefaultBufferSize(long bufferDurationUs) {
      int rate = getMaximumEncodedRateBytesPerSecond(outputEncoding);
      if (outputEncoding == C.ENCODING_AC3) {
        rate *= AC3_BUFFER_MULTIPLICATION_FACTOR;
      }
      return (int) (bufferDurationUs * rate / C.MICROS_PER_SECOND);
    }

    private int getPcmDefaultBufferSize(float maxAudioTrackPlaybackSpeed) {
      int minBufferSize =
          AudioTrack.getMinBufferSize(outputSampleRate, outputChannelConfig, outputEncoding);
      Assertions.checkState(minBufferSize != ERROR_BAD_VALUE);
      int multipliedBufferSize = minBufferSize * BUFFER_MULTIPLICATION_FACTOR;
      int minAppBufferSize = (int) durationUsToFrames(MIN_BUFFER_DURATION_US) * outputPcmFrameSize;
      int maxAppBufferSize =
          max(minBufferSize, (int) durationUsToFrames(MAX_BUFFER_DURATION_US) * outputPcmFrameSize);
      int bufferSize =
          Util.constrainValue(multipliedBufferSize, minAppBufferSize, maxAppBufferSize);
      if (maxAudioTrackPlaybackSpeed != 1f) {
        // Maintain the buffer duration by scaling the size accordingly.
        bufferSize = Math.round(bufferSize * maxAudioTrackPlaybackSpeed);
      }
      return bufferSize;
    }

    @RequiresApi(21)
    private static android.media.AudioAttributes getAudioTrackAttributesV21(
        AudioAttributes audioAttributes, boolean tunneling) {
      if (tunneling) {
        return getAudioTrackTunnelingAttributesV21();
      } else {
        return audioAttributes.getAudioAttributesV21();
      }
    }

    @RequiresApi(21)
    private static android.media.AudioAttributes getAudioTrackTunnelingAttributesV21() {
      return new android.media.AudioAttributes.Builder()
          .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
          .setFlags(android.media.AudioAttributes.FLAG_HW_AV_SYNC)
          .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
          .build();
    }

    public boolean outputModeIsOffload() {
      return outputMode == OUTPUT_MODE_OFFLOAD;
    }
  }
}
