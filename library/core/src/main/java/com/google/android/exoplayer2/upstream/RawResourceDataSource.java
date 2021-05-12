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
package com.google.android.exoplayer2.upstream;

import static com.google.android.exoplayer2.util.Util.castNonNull;
import static java.lang.Math.min;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.net.Uri;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.util.Assertions;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A {@link DataSource} for reading a raw resource inside the APK.
 *
 * <p>URIs supported by this source are of one of the forms:
 *
 * <ul>
 *   <li>{@code rawresource:///id}, where {@code id} is the integer identifier of a raw resource.
 *   <li>{@code android.resource:///id}, where {@code id} is the integer identifier of a raw
 *       resource.
 *   <li>{@code android.resource://[package]/[type/]name}, where {@code package} is the name of the
 *       package in which the resource is located, {@code type} is the resource type and {@code
 *       name} is the resource name. The package and the type are optional. Their default value is
 *       the package of this application and "raw", respectively. Using the two other forms is more
 *       efficient.
 * </ul>
 *
 * <p>{@link #buildRawResourceUri(int)} can be used to build supported {@link Uri}s.
 */
public final class RawResourceDataSource extends BaseDataSource {

  /**
   * Thrown when an {@link IOException} is encountered reading from a raw resource.
   */
  public static class RawResourceDataSourceException extends IOException {
    public RawResourceDataSourceException(String message) {
      super(message);
    }

    public RawResourceDataSourceException(IOException e) {
      super(e);
    }
  }

  /**
   * Builds a {@link Uri} for the specified raw resource identifier.
   *
   * @param rawResourceId A raw resource identifier (i.e. a constant defined in {@code R.raw}).
   * @return The corresponding {@link Uri}.
   */
  public static Uri buildRawResourceUri(int rawResourceId) {
    return Uri.parse(RAW_RESOURCE_SCHEME + ":///" + rawResourceId);
  }

  /** The scheme part of a raw resource URI. */
  public static final String RAW_RESOURCE_SCHEME = "rawresource";

  private final Resources resources;
  private final String packageName;

  @Nullable private Uri uri;
  @Nullable private AssetFileDescriptor assetFileDescriptor;
  @Nullable private InputStream inputStream;
  private long bytesRemaining;
  private boolean opened;

  /**
   * @param context A context.
   */
  public RawResourceDataSource(Context context) {
    super(/* isNetwork= */ false);
    this.resources = context.getResources();
    this.packageName = context.getPackageName();
  }

  @Override
  public long open(DataSpec dataSpec) throws RawResourceDataSourceException {
    Uri uri = dataSpec.uri;
    this.uri = uri;

    int resourceId;
    if (TextUtils.equals(RAW_RESOURCE_SCHEME, uri.getScheme())
        || (TextUtils.equals(ContentResolver.SCHEME_ANDROID_RESOURCE, uri.getScheme())
            && uri.getPathSegments().size() == 1
            && Assertions.checkNotNull(uri.getLastPathSegment()).matches("\\d+"))) {
      try {
        resourceId = Integer.parseInt(Assertions.checkNotNull(uri.getLastPathSegment()));
      } catch (NumberFormatException e) {
        throw new RawResourceDataSourceException("Resource identifier must be an integer.");
      }
    } else if (TextUtils.equals(ContentResolver.SCHEME_ANDROID_RESOURCE, uri.getScheme())) {
      String path = Assertions.checkNotNull(uri.getPath());
      if (path.startsWith("/")) {
        path = path.substring(1);
      }
      @Nullable String host = uri.getHost();
      String resourceName = (TextUtils.isEmpty(host) ? "" : (host + ":")) + path;
      resourceId =
          resources.getIdentifier(
              resourceName, /* defType= */ "raw", /* defPackage= */ packageName);
      if (resourceId == 0) {
        throw new RawResourceDataSourceException("Resource not found.");
      }
    } else {
      throw new RawResourceDataSourceException(
          "URI must either use scheme "
              + RAW_RESOURCE_SCHEME
              + " or "
              + ContentResolver.SCHEME_ANDROID_RESOURCE);
    }

    transferInitializing(dataSpec);
    AssetFileDescriptor assetFileDescriptor = resources.openRawResourceFd(resourceId);
    this.assetFileDescriptor = assetFileDescriptor;
    if (assetFileDescriptor == null) {
      throw new RawResourceDataSourceException("Resource is compressed: " + uri);
    }

    FileInputStream inputStream = new FileInputStream(assetFileDescriptor.getFileDescriptor());
    this.inputStream = inputStream;
    try {
      inputStream.skip(assetFileDescriptor.getStartOffset());
      long skipped = inputStream.skip(dataSpec.position);
      if (skipped < dataSpec.position) {
        // We expect the skip to be satisfied in full. If it isn't then we're probably trying to
        // skip beyond the end of the data.
        throw new EOFException();
      }
    } catch (IOException e) {
      throw new RawResourceDataSourceException(e);
    }

    if (dataSpec.length != C.LENGTH_UNSET) {
      bytesRemaining = dataSpec.length;
    } else {
      long assetFileDescriptorLength = assetFileDescriptor.getLength();
      // If the length is UNKNOWN_LENGTH then the asset extends to the end of the file.
      bytesRemaining =
          assetFileDescriptorLength == AssetFileDescriptor.UNKNOWN_LENGTH
              ? C.LENGTH_UNSET
              : (assetFileDescriptorLength - dataSpec.position);
    }

    opened = true;
    transferStarted(dataSpec);

    return bytesRemaining;
  }

  @Override
  public int read(byte[] buffer, int offset, int readLength) throws RawResourceDataSourceException {
    if (readLength == 0) {
      return 0;
    } else if (bytesRemaining == 0) {
      return C.RESULT_END_OF_INPUT;
    }

    int bytesRead;
    try {
      int bytesToRead =
          bytesRemaining == C.LENGTH_UNSET ? readLength : (int) min(bytesRemaining, readLength);
      bytesRead = castNonNull(inputStream).read(buffer, offset, bytesToRead);
    } catch (IOException e) {
      throw new RawResourceDataSourceException(e);
    }

    if (bytesRead == -1) {
      if (bytesRemaining != C.LENGTH_UNSET) {
        // End of stream reached having not read sufficient data.
        throw new RawResourceDataSourceException(new EOFException());
      }
      return C.RESULT_END_OF_INPUT;
    }
    if (bytesRemaining != C.LENGTH_UNSET) {
      bytesRemaining -= bytesRead;
    }
    bytesTransferred(bytesRead);
    return bytesRead;
  }

  @Override
  @Nullable
  public Uri getUri() {
    return uri;
  }

  @SuppressWarnings("Finally")
  @Override
  public void close() throws RawResourceDataSourceException {
    uri = null;
    try {
      if (inputStream != null) {
        inputStream.close();
      }
    } catch (IOException e) {
      throw new RawResourceDataSourceException(e);
    } finally {
      inputStream = null;
      try {
        if (assetFileDescriptor != null) {
          assetFileDescriptor.close();
        }
      } catch (IOException e) {
        throw new RawResourceDataSourceException(e);
      } finally {
        assetFileDescriptor = null;
        if (opened) {
          opened = false;
          transferEnded();
        }
      }
    }
  }

}
