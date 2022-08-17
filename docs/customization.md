---
title: Customization
---

At the core of the ExoPlayer library is the `Player` interface. A `Player`
exposes traditional high-level media player functionality such as the ability to
buffer media, play, pause and seek. The default implementation `ExoPlayer` is
designed to make few assumptions about (and hence impose few restrictions on)
the type of media being played, how and where it is stored, and how it is
rendered. Rather than implementing the loading and rendering of media directly,
`ExoPlayer` implementations delegate this work to components that are injected
when a player is created or when new media sources are passed to the player.
Components common to all `ExoPlayer` implementations are:

* `MediaSource` instances that define media to be played, load the media, and
  from which the loaded media can be read. `MediaSource` instances are created
  from `MediaItem`s by a `MediaSource.Factory` inside the player. They can also
  be passed directly to the player using the [media source based playlist API].
* A `MediaSource.Factory` that converts `MediaItem`s to `MediaSource`s. The
  `MediaSource.Factory` is injected when the player is created.
* `Renderer`s that render individual components of the media. `Renderer`s are
  injected when the player is created.
* A `TrackSelector` that selects tracks provided by the `MediaSource` to be
  consumed by each of the available `Renderer`s. A `TrackSelector` is injected
  when the player is created.
* A `LoadControl` that controls when the `MediaSource` buffers more media, and
  how much media is buffered. A `LoadControl` is injected when the player is
  created.
* A `LivePlaybackSpeedControl` that controls the playback speed during live
  playbacks to allow the player to stay close to a configured live offset. A
  `LivePlaybackSpeedControl` is injected when the player is created.

The concept of injecting components that implement pieces of player
functionality is present throughout the library. The default implementations of
some components delegate work to further injected components. This allows many
sub-components to be individually replaced with implementations that are
configured in a custom way.

## Player customization ##

Some common examples of customizing the player by injecting components are
described below.

### Configuring the network stack ###

We have a page about [customizing the network stack used by ExoPlayer].

### Caching data loaded from the network ###

To temporarily cache media, or for
[playing downloaded media]({{ site.baseurl }}/downloading-media.html#playing-downloaded-content),
you can inject a `CacheDataSource.Factory` into the `DefaultMediaSourceFactory`:

~~~
DataSource.Factory cacheDataSourceFactory =
    new CacheDataSource.Factory()
        .setCache(simpleCache)
        .setUpstreamDataSourceFactory(httpDataSourceFactory);

ExoPlayer player = new ExoPlayer.Builder(context)
    .setMediaSourceFactory(
        new DefaultMediaSourceFactory(context)
            .setDataSourceFactory(cacheDataSourceFactory))
    .build();
~~~
{: .language-java}

### Customizing server interactions ###

Some apps may want to intercept HTTP requests and responses. You may want to
inject custom request headers, read the server's response headers, modify the
requests' URIs, etc. For example, your app may authenticate itself by injecting
a token as a header when requesting the media segments.

The following example demonstrates how to implement these behaviors by
injecting a custom `DataSource.Factory` into the `DefaultMediaSourceFactory`:

~~~
DataSource.Factory dataSourceFactory = () -> {
  HttpDataSource dataSource = httpDataSourceFactory.createDataSource();
  // Set a custom authentication request header.
  dataSource.setRequestProperty("Header", "Value");
  return dataSource;
};

ExoPlayer player = new ExoPlayer.Builder(context)
    .setMediaSourceFactory(
        new DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory))
    .build();
~~~
{: .language-java}

In the code snippet above, the injected `HttpDataSource` includes the header
`"Header: Value"` in every HTTP request. This behavior is *fixed* for every
interaction with an HTTP source.

For a more granular approach, you can inject just-in-time behavior using a
`ResolvingDataSource`. The following code snippet shows how to inject
request headers just before interacting with an HTTP source:

~~~
DataSource.Factory dataSourceFactory = new ResolvingDataSource.Factory(
    httpDataSourceFactory,
    // Provide just-in-time request headers.
    dataSpec -> dataSpec.withRequestHeaders(getCustomHeaders(dataSpec.uri)));
~~~
{: .language-java}

You may also use a `ResolvingDataSource` to perform
just-in-time modifications of the URI, as shown in the following snippet:

~~~
DataSource.Factory dataSourceFactory = new ResolvingDataSource.Factory(
    httpDataSourceFactory,
    // Provide just-in-time URI resolution logic.
    dataSpec -> dataSpec.withUri(resolveUri(dataSpec.uri)));
~~~
{: .language-java}

### Customizing error handling ###

Implementing a custom [LoadErrorHandlingPolicy][] allows apps to customize the
way ExoPlayer reacts to load errors. For example, an app may want to fail fast
instead of retrying many times, or may want to customize the back-off logic that
controls how long the player waits between each retry. The following snippet
shows how to implement custom back-off logic:

~~~
LoadErrorHandlingPolicy loadErrorHandlingPolicy =
    new DefaultLoadErrorHandlingPolicy() {
      @Override
      public long getRetryDelayMsFor(LoadErrorInfo loadErrorInfo) {
        // Implement custom back-off logic here.
      }
    };

ExoPlayer player =
    new ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            new DefaultMediaSourceFactory(context)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy))
        .build();
~~~
{: .language-java}

The `LoadErrorInfo` argument contains more information about the failed load to
customize the logic based on the error type or the failed request.

### Customizing extractor flags ###

Extractor flags can be used to customize how individual formats are extracted
from progressive media. They can be set on the `DefaultExtractorsFactory` that's
provided to the `DefaultMediaSourceFactory`. The following example passes a flag
that enables index-based seeking for MP3 streams.

~~~
DefaultExtractorsFactory extractorsFactory =
    new DefaultExtractorsFactory()
        .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING);

ExoPlayer player = new ExoPlayer.Builder(context)
    .setMediaSourceFactory(
        new DefaultMediaSourceFactory(context, extractorsFactory))
    .build();
~~~
{: .language-java}

### Enabling constant bitrate seeking ###

For MP3, ADTS and AMR streams, you can enable approximate seeking using a
constant bitrate assumption with `FLAG_ENABLE_CONSTANT_BITRATE_SEEKING` flags.
These flags can be set for individual extractors using the individual
`DefaultExtractorsFactory.setXyzExtractorFlags` methods as described above. To
enable constant bitrate seeking for all extractors that support it, use
`DefaultExtractorsFactory.setConstantBitrateSeekingEnabled`.

~~~
DefaultExtractorsFactory extractorsFactory =
    new DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true);
~~~
{: .language-java}

The `ExtractorsFactory` can then be injected via `DefaultMediaSourceFactory` as
described for customizing extractor flags above.


### Enabling asynchronous buffer queueing ###

Asynchronous buffer queueing is an enhancement in ExoPlayer's rendering
pipeline, which operates `MediaCodec` instances in [asynchronous mode][] and
uses additional threads to schedule decoding and rendering of data. Enabling it
can reduce dropped frames and audio underruns.

Asynchronous buffer queueing is enabled by default on devices running Android 12
and above, and can be enabled manually from Android 6. Consider enabling the
feature for specific devices on which you observe dropped frames or audio
underruns, particularly when playing DRM protected or high frame rate content.

In the simplest case, you need to inject a `DefaultRenderersFactory` to the
player as follows:

~~~
DefaultRenderersFactory renderersFactory =
    new DefaultRenderersFactory(context)
        .forceEnableMediaCodecAsynchronousQueueing();
ExoPlayer exoPlayer = new ExoPlayer.Builder(context, renderersFactory).build();
~~~
{: .language-java}

If you're instantiating renderers directly, pass a
`AsynchronousMediaCodecAdapter.Factory` to the `MediaCodecVideoRenderer` and
`MediaCodecAudioRenderer` constructors.

### Intercepting method calls with `ForwardingPlayer` ###

You can customize some of the behavior of a `Player` instance by wrapping it in
a subclass of `ForwardingPlayer` and overriding methods in order to do any of
the following:

* Access parameters before passing them to the delegate `Player`.
* Access the return value from the delegate `Player` before returning it.
* Re-implement the method completely.

When overriding `ForwardingPlayer` methods it's important to ensure the
implementation remains self-consistent and compliant with the `Player`
interface, especially when dealing with methods that are intended to have
identical or related behavior. For example, if you want to override every 'play'
operation, you need to override both `ForwardingPlayer.play` and
`ForwardingPlayer.setPlayWhenReady`, because a caller will expect the behavior
of these methdods to be identical when `playWhenReady = true`. Similarly, if you
want to change the seek-forward increment you need to override both
`ForwardingPlayer.seekForward` to perform a seek with your customized increment,
and `ForwardingPlayer.getSeekForwardIncrement` in order to report the correct
customized increment back to the caller.

## MediaSource customization ##

The examples above inject customized components for use during playback of all
`MediaItem`s that are passed to the player. Where fine-grained customization is
required, it's also possible to inject customized components into individual
`MediaSource` instances, which can be passed directly to the player. The example
below shows how to customize a `ProgressiveMediaSource` to use a custom
`DataSource.Factory`, `ExtractorsFactory` and `LoadErrorHandlingPolicy`:

~~~
ProgressiveMediaSource mediaSource =
    new ProgressiveMediaSource.Factory(
            customDataSourceFactory, customExtractorsFactory)
        .setLoadErrorHandlingPolicy(customLoadErrorHandlingPolicy)
        .createMediaSource(MediaItem.fromUri(streamUri));
~~~
{: .language-java}

## Creating custom components ##

The library provides default implementations of the components listed at the top
of this page for common use cases. An `ExoPlayer` can use these components, but
may also be built to use custom implementations if non-standard behaviors are
required. Some use cases for custom implementations are:

* `Renderer` &ndash; You may want to implement a custom `Renderer` to handle a
  media type not supported by the default implementations provided by the
  library.
* `TrackSelector` &ndash; Implementing a custom `TrackSelector` allows an app
  developer to change the way in which tracks exposed by a `MediaSource` are
  selected for consumption by each of the available `Renderer`s.
* `LoadControl` &ndash; Implementing a custom `LoadControl` allows an app
  developer to change the player's buffering policy.
* `Extractor` &ndash; If you need to support a container format not currently
  supported by the library, consider implementing a custom `Extractor` class.
* `MediaSource` &ndash; Implementing a custom `MediaSource` class may be
  appropriate if you wish to obtain media samples to feed to renderers in a
  custom way, or if you wish to implement custom `MediaSource` compositing
  behavior.
* `MediaSource.Factory` &ndash; Implementing a custom `MediaSource.Factory`
  allows an application to customize the way in which `MediaSource`s are created
  from `MediaItem`s.
* `DataSource` &ndash; ExoPlayer’s upstream package already contains a number of
  `DataSource` implementations for different use cases. You may want to
  implement you own `DataSource` class to load data in another way, such as over
  a custom protocol, using a custom HTTP stack, or from a custom persistent
  cache.

When building custom components, we recommend the following:

* If a custom component needs to report events back to the app, we recommend
  that you do so using the same model as existing ExoPlayer components, for
  example using `EventDispatcher` classes or passing a `Handler` together with
  a listener to the constructor of the component.
* We recommended that custom components use the same model as existing ExoPlayer
  components to allow reconfiguration by the app during playback. To do this,
  custom components should implement `PlayerMessage.Target` and receive
  configuration changes in the `handleMessage` method. Application code should
  pass configuration changes by calling ExoPlayer’s `createMessage` method,
  configuring the message, and sending it to the component using
  `PlayerMessage.send`. Sending messages to be delivered on the playback thread
  ensures that they are executed in order with any other operations being
  performed on the player.

[customizing the network stack used by ExoPlayer]: {{ site.baseurl }}/network-stacks.html
[LoadErrorHandlingPolicy]: {{ site.exo_sdk }}/upstream/LoadErrorHandlingPolicy.html
[media source based playlist API]: {{ site.baseurl }}/media-sources.html#media-source-based-playlist-api
[asynchronous mode]: https://developer.android.com/reference/android/media/MediaCodec#asynchronous-processing-using-buffers

