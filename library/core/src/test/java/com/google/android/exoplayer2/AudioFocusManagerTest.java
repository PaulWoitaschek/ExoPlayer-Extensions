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
package com.google.android.exoplayer2;

import static com.google.android.exoplayer2.AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY;
import static com.google.android.exoplayer2.AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY;
import static com.google.android.exoplayer2.AudioFocusManager.PLAYER_COMMAND_WAIT_FOR_CALLBACK;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.robolectric.annotation.Config.TARGET_SDK;

import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.util.Util;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAudioManager;

/** Unit tests for {@link AudioFocusManager}. */
@RunWith(AndroidJUnit4.class)
public class AudioFocusManagerTest {
  private static final int NO_COMMAND_RECEIVED = ~PLAYER_COMMAND_WAIT_FOR_CALLBACK;

  private AudioFocusManager audioFocusManager;
  private TestPlayerControl testPlayerControl;

  private AudioManager audioManager;

  @Before
  public void setUp() {
    audioManager =
        (AudioManager)
            ApplicationProvider.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);

    testPlayerControl = new TestPlayerControl();
    audioFocusManager =
        new AudioFocusManager(
            ApplicationProvider.getApplicationContext(),
            new Handler(Looper.myLooper()),
            testPlayerControl);
  }

  @Test
  public void setAudioAttributes_withNullUsage_doesNotManageAudioFocus() {
    // Ensure that NULL audio attributes -> don't manage audio focus
    audioFocusManager.setAudioAttributes(/* audioAttributes= */ null);

    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ false, Player.STATE_IDLE))
        .isEqualTo(PLAYER_COMMAND_DO_NOT_PLAY);
    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
    ShadowAudioManager.AudioFocusRequest request =
        Shadows.shadowOf(audioManager).getLastAudioFocusRequest();
    assertThat(request).isNull();
  }

  @Test
  @Config(maxSdk = 25)
  public void setAudioAttributes_withNullUsage_releasesAudioFocus() {
    // Create attributes and request audio focus.
    AudioAttributes media = new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build();
    Shadows.shadowOf(audioManager)
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    audioFocusManager.setAudioAttributes(media);
    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
    ShadowAudioManager.AudioFocusRequest request =
        Shadows.shadowOf(audioManager).getLastAudioFocusRequest();
    assertThat(request.durationHint).isEqualTo(AudioManager.AUDIOFOCUS_GAIN);

    // Ensure that setting null audio attributes with audio focus releases audio focus.
    audioFocusManager.setAudioAttributes(/* audioAttributes= */ null);

    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
    AudioManager.OnAudioFocusChangeListener lastRequest =
        Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusListener();
    assertThat(lastRequest).isNotNull();
  }

  @Test
  @Config(minSdk = 26, maxSdk = TARGET_SDK)
  public void setAudioAttributes_withNullUsage_releasesAudioFocus_v26() {
    // Create attributes and request audio focus.
    AudioAttributes media = new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build();
    Shadows.shadowOf(audioManager)
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    audioFocusManager.setAudioAttributes(media);
    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
    ShadowAudioManager.AudioFocusRequest request =
        Shadows.shadowOf(audioManager).getLastAudioFocusRequest();
    assertThat(getAudioFocusGainFromRequest(request)).isEqualTo(AudioManager.AUDIOFOCUS_GAIN);

    // Ensure that setting null audio attributes with audio focus releases audio focus.
    audioFocusManager.setAudioAttributes(/* audioAttributes= */ null);
    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
    AudioFocusRequest lastRequest =
        Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusRequest();
    assertThat(lastRequest).isNotNull();
  }

  @Test
  public void setAudioAttributes_withUsageAlarm_throwsIllegalArgumentException() {
    // Ensure that audio attributes that map to AUDIOFOCUS_GAIN_TRANSIENT* throw.
    AudioAttributes alarm = new AudioAttributes.Builder().setUsage(C.USAGE_ALARM).build();
    try {
      audioFocusManager.setAudioAttributes(alarm);
      fail();
    } catch (IllegalArgumentException e) {
      // Expected
    }
  }

  @Test
  public void setAudioAttributes_withUsageMedia_usesAudioFocusGain() {
    // Ensure setting media type audio attributes requests AUDIOFOCUS_GAIN.
    AudioAttributes media = new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build();
    Shadows.shadowOf(audioManager)
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);

    audioFocusManager.setAudioAttributes(media);

    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
    ShadowAudioManager.AudioFocusRequest request =
        Shadows.shadowOf(audioManager).getLastAudioFocusRequest();
    assertThat(getAudioFocusGainFromRequest(request)).isEqualTo(AudioManager.AUDIOFOCUS_GAIN);
  }

  @Test
  public void setAudioAttributes_inStateEnded_requestsAudioFocus() {
    // Ensure setting audio attributes when player is in STATE_ENDED requests audio focus.
    AudioAttributes media = new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build();
    Shadows.shadowOf(audioManager)
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);

    audioFocusManager.setAudioAttributes(media);

    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_ENDED))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
    ShadowAudioManager.AudioFocusRequest request =
        Shadows.shadowOf(audioManager).getLastAudioFocusRequest();
    assertThat(getAudioFocusGainFromRequest(request)).isEqualTo(AudioManager.AUDIOFOCUS_GAIN);
  }

  @Test
  public void updateAudioFocus_idleToBuffering_setsPlayerCommandPlayWhenReady() {
    // Ensure that when playWhenReady is true while the player is IDLE, audio focus is only
    // requested after calling prepare (= changing the state to BUFFERING).
    AudioAttributes media = new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build();
    Shadows.shadowOf(audioManager)
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    audioFocusManager.setAudioAttributes(media);

    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_IDLE))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
    assertThat(Shadows.shadowOf(audioManager).getLastAudioFocusRequest()).isNull();
    assertThat(
            audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_BUFFERING))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
    ShadowAudioManager.AudioFocusRequest request =
        Shadows.shadowOf(audioManager).getLastAudioFocusRequest();
    assertThat(getAudioFocusGainFromRequest(request)).isEqualTo(AudioManager.AUDIOFOCUS_GAIN);
  }

  @Test
  public void updateAudioFocus_pausedToPlaying_setsPlayerCommandPlayWhenReady() {
    AudioAttributes media = new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build();
    Shadows.shadowOf(audioManager)
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    audioFocusManager.setAudioAttributes(media);

    // Audio focus should not be requested yet, because playWhenReady=false.
    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ false, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_DO_NOT_PLAY);
    assertThat(Shadows.shadowOf(audioManager).getLastAudioFocusRequest()).isNull();

    // Audio focus should be requested now that playWhenReady=true.
    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
    ShadowAudioManager.AudioFocusRequest request =
        Shadows.shadowOf(audioManager).getLastAudioFocusRequest();
    assertThat(getAudioFocusGainFromRequest(request)).isEqualTo(AudioManager.AUDIOFOCUS_GAIN);
  }

  // See https://github.com/google/ExoPlayer/issues/7182 for context.
  @Test
  public void updateAudioFocus_pausedToPlaying_withTransientLoss_setsPlayerCommandPlayWhenReady() {
    AudioAttributes media = new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build();
    Shadows.shadowOf(audioManager)
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    audioFocusManager.setAudioAttributes(media);

    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);

    // Simulate transient focus loss.
    audioFocusManager.getFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);

    // Focus should be re-requested, rather than staying in a state of transient focus loss.
    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
  }

  @Test
  public void updateAudioFocus_pausedToPlaying_withTransientDuck_setsPlayerCommandPlayWhenReady() {
    AudioAttributes media = new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build();
    Shadows.shadowOf(audioManager)
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    audioFocusManager.setAudioAttributes(media);

    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);

    // Simulate transient ducking.
    audioFocusManager
        .getFocusListener()
        .onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
    assertThat(testPlayerControl.lastVolumeMultiplier).isLessThan(1.0f);

    // Focus should be re-requested, rather than staying in a state of transient ducking. This
    // should restore the volume to 1.0.
    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
    assertThat(testPlayerControl.lastVolumeMultiplier).isEqualTo(1.0f);
  }

  @Test
  public void updateAudioFocus_abandonFocusWhenDucked_restoresFullVolume() {
    AudioAttributes media = new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build();
    Shadows.shadowOf(audioManager)
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    audioFocusManager.setAudioAttributes(media);

    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);

    // Simulate transient ducking.
    audioFocusManager
        .getFocusListener()
        .onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
    assertThat(testPlayerControl.lastVolumeMultiplier).isLessThan(1.0f);

    // Configure the manager to no longer handle audio focus.
    audioFocusManager.setAudioAttributes(null);

    // Focus should be abandoned, which should restore the volume to 1.0.
    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
    assertThat(testPlayerControl.lastVolumeMultiplier).isEqualTo(1.0f);
  }

  @Test
  @Config(maxSdk = 25)
  public void updateAudioFocus_readyToIdle_abandonsAudioFocus() {
    // Ensure that stopping the player (=changing state to idle) abandons audio focus.
    AudioAttributes media =
        new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.CONTENT_TYPE_SPEECH)
            .build();
    Shadows.shadowOf(audioManager)
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    audioFocusManager.setAudioAttributes(media);
    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
    assertThat(Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusListener()).isNull();

    ShadowAudioManager.AudioFocusRequest request =
        Shadows.shadowOf(audioManager).getLastAudioFocusRequest();
    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_IDLE))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
    assertThat(Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusListener())
        .isEqualTo(request.listener);
  }

  @Test
  @Config(minSdk = 26, maxSdk = TARGET_SDK)
  public void updateAudioFocus_readyToIdle_abandonsAudioFocus_v26() {
    // Ensure that stopping the player (=changing state to idle) abandons audio focus.
    AudioAttributes media =
        new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.CONTENT_TYPE_SPEECH)
            .build();
    Shadows.shadowOf(audioManager)
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    audioFocusManager.setAudioAttributes(media);

    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
    assertThat(Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusRequest()).isNull();

    ShadowAudioManager.AudioFocusRequest request =
        Shadows.shadowOf(audioManager).getLastAudioFocusRequest();
    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_IDLE))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
    assertThat(Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusRequest())
        .isEqualTo(request.audioFocusRequest);
  }

  @Test
  @Config(maxSdk = 25)
  public void updateAudioFocus_readyToIdle_withoutHandlingAudioFocus_isNoOp() {
    // Ensure that changing state to idle is a no-op if audio focus isn't handled.
    Shadows.shadowOf(audioManager)
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    audioFocusManager.setAudioAttributes(null);

    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ false, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_DO_NOT_PLAY);
    assertThat(Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusListener()).isNull();
    ShadowAudioManager.AudioFocusRequest request =
        Shadows.shadowOf(audioManager).getLastAudioFocusRequest();
    assertThat(request).isNull();

    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ false, Player.STATE_IDLE))
        .isEqualTo(PLAYER_COMMAND_DO_NOT_PLAY);
    assertThat(Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusListener()).isNull();
  }

  @Test
  @Config(minSdk = 26, maxSdk = TARGET_SDK)
  public void updateAudioFocus_readyToIdle_withoutHandlingAudioFocus_isNoOp_v26() {
    // Ensure that changing state to idle is a no-op if audio focus isn't handled.
    Shadows.shadowOf(audioManager)
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    audioFocusManager.setAudioAttributes(null);

    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ false, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_DO_NOT_PLAY);
    assertThat(Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusRequest()).isNull();
    ShadowAudioManager.AudioFocusRequest request =
        Shadows.shadowOf(audioManager).getLastAudioFocusRequest();
    assertThat(request).isNull();

    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ false, Player.STATE_IDLE))
        .isEqualTo(PLAYER_COMMAND_DO_NOT_PLAY);
    assertThat(Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusRequest()).isNull();
  }

  @Test
  public void release_doesNotCallPlayerControlToRestoreVolume() {
    AudioAttributes media = new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build();
    Shadows.shadowOf(audioManager)
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    audioFocusManager.setAudioAttributes(media);

    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);

    // Simulate transient ducking.
    audioFocusManager
        .getFocusListener()
        .onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
    assertThat(testPlayerControl.lastVolumeMultiplier).isLessThan(1.0f);

    audioFocusManager.release();

    // PlaybackController.setVolumeMultiplier should not have been called to restore the volume.
    assertThat(testPlayerControl.lastVolumeMultiplier).isLessThan(1.0f);
  }

  @Test
  public void onAudioFocusChange_withDuckEnabled_volumeReducedAndRestored() {
    // Ensure that the volume multiplier is adjusted when audio focus is lost to
    // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, and returns to the default value after focus is
    // regained.
    AudioAttributes media = new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build();
    Shadows.shadowOf(audioManager)
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    audioFocusManager.setAudioAttributes(media);

    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);

    audioFocusManager
        .getFocusListener()
        .onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);

    assertThat(testPlayerControl.lastVolumeMultiplier).isLessThan(1.0f);
    assertThat(testPlayerControl.lastPlayerCommand).isEqualTo(NO_COMMAND_RECEIVED);
    audioFocusManager.getFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
    assertThat(testPlayerControl.lastVolumeMultiplier).isEqualTo(1.0f);
  }

  @Test
  public void onAudioFocusChange_withPausedWhenDucked_sendsCommandWaitForCallback() {
    // Ensure that the player is commanded to pause when audio focus is lost with
    // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK and the content type is CONTENT_TYPE_SPEECH.
    AudioAttributes media =
        new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.CONTENT_TYPE_SPEECH)
            .build();
    Shadows.shadowOf(audioManager)
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    audioFocusManager.setAudioAttributes(media);

    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);

    audioFocusManager
        .getFocusListener()
        .onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
    assertThat(testPlayerControl.lastPlayerCommand).isEqualTo(PLAYER_COMMAND_WAIT_FOR_CALLBACK);
    assertThat(testPlayerControl.lastVolumeMultiplier).isEqualTo(1.0f);
    audioFocusManager.getFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
    assertThat(testPlayerControl.lastPlayerCommand).isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
  }

  @Test
  public void onAudioFocusChange_withTransientLoss_sendsCommandWaitForCallback() {
    // Ensure that the player is commanded to pause when audio focus is lost with
    // AUDIOFOCUS_LOSS_TRANSIENT.
    AudioAttributes media = new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build();
    Shadows.shadowOf(audioManager)
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    audioFocusManager.setAudioAttributes(media);

    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);

    audioFocusManager.getFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
    assertThat(testPlayerControl.lastVolumeMultiplier).isEqualTo(1.0f);
    assertThat(testPlayerControl.lastPlayerCommand).isEqualTo(PLAYER_COMMAND_WAIT_FOR_CALLBACK);
  }

  @Test
  @Config(maxSdk = 25)
  public void onAudioFocusChange_withFocusLoss_sendsDoNotPlayAndAbandonsFocus() {
    // Ensure that AUDIOFOCUS_LOSS causes AudioFocusManager to pause playback and abandon audio
    // focus.
    AudioAttributes media =
        new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.CONTENT_TYPE_SPEECH)
            .build();
    Shadows.shadowOf(audioManager)
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    audioFocusManager.setAudioAttributes(media);

    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
    assertThat(Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusListener()).isNull();

    ShadowAudioManager.AudioFocusRequest request =
        Shadows.shadowOf(audioManager).getLastAudioFocusRequest();
    request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
    assertThat(testPlayerControl.lastPlayerCommand).isEqualTo(PLAYER_COMMAND_DO_NOT_PLAY);
    assertThat(Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusListener())
        .isEqualTo(request.listener);
  }

  @Test
  @Config(minSdk = 26, maxSdk = TARGET_SDK)
  public void onAudioFocusChange_withFocusLoss_sendsDoNotPlayAndAbandonsFocus_v26() {
    // Ensure that AUDIOFOCUS_LOSS causes AudioFocusManager to pause playback and abandon audio
    // focus.
    AudioAttributes media =
        new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.CONTENT_TYPE_SPEECH)
            .build();
    Shadows.shadowOf(audioManager)
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    audioFocusManager.setAudioAttributes(media);

    assertThat(audioFocusManager.updateAudioFocus(/* playWhenReady= */ true, Player.STATE_READY))
        .isEqualTo(PLAYER_COMMAND_PLAY_WHEN_READY);
    assertThat(Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusRequest()).isNull();

    audioFocusManager.getFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
    assertThat(testPlayerControl.lastPlayerCommand).isEqualTo(PLAYER_COMMAND_DO_NOT_PLAY);
    assertThat(Shadows.shadowOf(audioManager).getLastAbandonedAudioFocusRequest())
        .isEqualTo(Shadows.shadowOf(audioManager).getLastAudioFocusRequest().audioFocusRequest);
  }

  private int getAudioFocusGainFromRequest(ShadowAudioManager.AudioFocusRequest audioFocusRequest) {
    return Util.SDK_INT >= 26
        ? audioFocusRequest.audioFocusRequest.getFocusGain()
        : audioFocusRequest.durationHint;
  }

  private static class TestPlayerControl implements AudioFocusManager.PlayerControl {
    private float lastVolumeMultiplier = 1.0f;
    private int lastPlayerCommand = NO_COMMAND_RECEIVED;

    @Override
    public void setVolumeMultiplier(float volumeMultiplier) {
      lastVolumeMultiplier = volumeMultiplier;
    }

    @Override
    public void executePlayerCommand(int playerCommand) {
      lastPlayerCommand = playerCommand;
    }
  }
}
