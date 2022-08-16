---
title: Playlists
---

The playlist API is defined by the `Player` interface, which is implemented by
all `ExoPlayer` implementations. It enables sequential playback of multiple
media items. The following example shows how to start playback of a playlist
containing two videos:

~~~
// Build the media items.
MediaItem firstItem = MediaItem.fromUri(firstVideoUri);
MediaItem secondItem = MediaItem.fromUri(secondVideoUri);
// Add the media items to be played.
player.addMediaItem(firstItem);
player.addMediaItem(secondItem);
// Prepare the player.
player.prepare();
// Start the playback.
player.play();
~~~
{: .language-java}

Transitions between items in a playlist are seamless. There's no requirement
that they're of the same format (e.g., it’s fine for a playlist to contain both
H264 and VP9 videos). They may even be of different types (e.g., it’s fine for a
playlist to contain both videos and audio only streams). It's allowed to use the
same `MediaItem` multiple times within a playlist.

## Modifying the playlist

It's possible to dynamically modify a playlist by adding, moving and removing
media items. This can be done both before and during playback by calling the
corresponding playlist API methods:

~~~
// Adds a media item at position 1 in the playlist.
player.addMediaItem(/* index= */ 1, MediaItem.fromUri(thirdUri));
// Moves the third media item from position 2 to the start of the playlist.
player.moveMediaItem(/* currentIndex= */ 2, /* newIndex= */ 0);
// Removes the first item from the playlist.
player.removeMediaItem(/* index= */ 0);
~~~
{: .language-java}

Replacing and clearing the entire playlist are also supported:

~~~
// Replaces the playlist with a new one.
List<MediaItem> newItems = ImmutableList.of(
    MediaItem.fromUri(fourthUri),
    MediaItem.fromUri(fifthUri));
player.setMediaItems(newItems, /* resetPosition= */ true);
// Clears the playlist. If prepared, the player transitions to the ended state.
player.clearMediaItems();
~~~
{: .language-java}

The player automatically handles modifications during playback in the correct
way. For example if the currently playing media item is moved, playback is not
interrupted and its new successor will be played upon completion. If the
currently playing `MediaItem` is removed, the player will automatically move to
playing the first remaining successor, or transition to the ended state if no
such successor exists.

## Querying the playlist

The playlist can be queried using `Player.getMediaItemCount` and
`Player.getMediaItemAt`. The currently playing media item can be queried
by calling `Player.getCurrentMediaItem`. There are also other convenience
methods like `Player.hasNextMediaItem` or `Player.getNextMediaItemIndex` to
simplify navigation in the playlist.

## Repeat modes

The player supports 3 repeat modes that can be set at any time with
`Player.setRepeatMode`:

* `Player.REPEAT_MODE_OFF`: The playlist isn't repeated and the player will
   transition to `Player.STATE_ENDED` once the last item in the playlist has
   been played.
* `Player.REPEAT_MODE_ONE`: The current item is repeated in an endless loop.
   Methods like `Player.seekToNextMediaItem` will ignore this and seek to the
   next item in the list, which will then be repeated in an endless loop.
* `Player.REPEAT_MODE_ALL`: The entire playlist is repeated in an endless loop.

## Shuffle mode

Shuffle mode can be enabled or disabled at any time with
`Player.setShuffleModeEnabled`. When in shuffle mode, the player will play the
playlist in a precomputed, randomized order. All items will be played once and
the shuffle mode can also be combined with `Player.REPEAT_MODE_ALL` to repeat
the same randomized order in an endless loop. When shuffle mode is turned off,
playback continues from the current item at its original position in the
playlist.

Note that the indices as returned by methods like
`Player.getCurrentMediaItemIndex` always refer to the original, unshuffled
order. Similarly, `Player.seekToNextMediaItem` will not play the item at
`player.getCurrentMediaItemIndex() + 1`, but the next item according to the
shuffle order. Inserting new items in the playlist or removing items will keep
the existing shuffled order unchanged as far as possible.

### Setting a custom shuffle order

By default the player supports shuffling by using the `DefaultShuffleOrder`.
This can be customized by providing a custom shuffle order implementation, or by
setting a custom order in the `DefaultShuffleOrder` constructor:

~~~
// Set a custom shuffle order for the 5 items currently in the playlist:
exoPlayer.setShuffleOrder(
    new DefaultShuffleOrder(new int[] {3, 1, 0, 4, 2}, randomSeed));
// Enable shuffle mode.
exoPlayer.setShuffleModeEnabled(/* shuffleModeEnabled= */ true);
~~~
{: .language-java}

## Identifying playlist items

To identify playlist items, `MediaItem.mediaId` can be set when building the
item:

~~~
// Build a media item with a media ID.
MediaItem mediaItem =
    new MediaItem.Builder().setUri(uri).setMediaId(mediaId).build();
~~~
{: .language-java}

If an app does not explicitly define a media ID for a media item, the string
representation of the URI is used.

## Associating app data with playlist items

In addition to an ID, each media item can also be configured with a custom tag,
which can be any app provided object. One use of custom tags is to attach
metadata to each media item:

~~~
// Build a media item with a custom tag.
MediaItem mediaItem =
    new MediaItem.Builder().setUri(uri).setTag(metadata).build();
~~~
{: .language-java}


## Detecting when playback transitions to another media item

When playback transitions to another media item, or starts repeating the same
media item, `Listener.onMediaItemTransition(MediaItem,
@MediaItemTransitionReason)` is called. This callback receives the new media
item, along with a `@MediaItemTransitionReason` indicating why the transition
occurred. A common use case for `onMediaItemTransition` is to update the
application's UI for the new media item:

~~~
@Override
public void onMediaItemTransition(
    @Nullable MediaItem mediaItem, @MediaItemTransitionReason int reason) {
  updateUiForPlayingMediaItem(mediaItem);
}
~~~
{: .language-java}

If the metadata required to update the UI is attached to each media item using
custom tags, then an implementation might look like:

~~~
@Override
public void onMediaItemTransition(
    @Nullable MediaItem mediaItem, @MediaItemTransitionReason int reason) {
  @Nullable CustomMetadata metadata = null;
  if (mediaItem != null && mediaItem.localConfiguration != null) {
    metadata = (CustomMetadata) mediaItem.localConfiguration.tag;
  }
  updateUiForPlayingMediaItem(metadata);
}
~~~
{: .language-java}

## Detecting when the playlist changes

When a media item is added, removed or moved,
`Listener.onTimelineChanged(Timeline, @TimelineChangeReason)` is called
immediately with `TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED`. This callback is
called even when the player has not yet been prepared.

~~~
@Override
public void onTimelineChanged(
    Timeline timeline, @TimelineChangeReason int reason) {
  if (reason == TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
    // Update the UI according to the modified playlist (add, move or remove).
    updateUiForPlaylist(timeline);
  }
}
~~~
{: .language-java}

When information such as the duration of a media item in the playlist becomes
available, the `Timeline` will be updated and `onTimelineChanged` will be called
with `TIMELINE_CHANGE_REASON_SOURCE_UPDATE`. Other reasons that can cause a
timeline update include:

* A manifest becoming available after preparing an adaptive media item.
* A manifest being updated periodically during playback of a live stream.
