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
package com.google.android.exoplayer2.extractor.ogg;

import com.google.android.exoplayer2.testutil.ExtractorAsserts;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

/**
 * Unit tests for {@link OggExtractor} that use parameterization to test a range of behaviours.
 *
 * <p>For non-parameterized tests see {@link OggExtractorNonParameterizedTest}.
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
public final class OggExtractorParameterizedTest {

  @Parameters(name = "{0}")
  public static List<Object[]> params() {
    return ExtractorAsserts.configs();
  }

  @Parameter public ExtractorAsserts.SimulationConfig simulationConfig;

  @Test
  public void opus() throws Exception {
    ExtractorAsserts.assertBehavior(OggExtractor::new, "media/ogg/bear.opus", simulationConfig);
  }

  @Test
  public void flac() throws Exception {
    ExtractorAsserts.assertBehavior(OggExtractor::new, "media/ogg/bear_flac.ogg", simulationConfig);
  }

  @Test
  public void flacNoSeektable() throws Exception {
    ExtractorAsserts.assertBehavior(
        OggExtractor::new, "media/ogg/bear_flac_noseektable.ogg", simulationConfig);
  }

  @Test
  public void vorbis() throws Exception {
    ExtractorAsserts.assertBehavior(
        OggExtractor::new, "media/ogg/bear_vorbis.ogg", simulationConfig);
  }

  // Ensure the extractor can handle non-contiguous pages by using a file with 10 bytes of garbage
  // data before the start of the second page.
  @Test
  public void vorbisWithGapBeforeSecondPage() throws Exception {
    ExtractorAsserts.assertBehavior(
        OggExtractor::new, "media/ogg/bear_vorbis_gap.ogg", simulationConfig);
  }
}
