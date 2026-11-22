package com.exodidio.tune.ui.navigation

import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.player.RepeatMode

/** Transport affordance policy for the active queue order. */
internal fun PlaybackQueueSnapshot.canNavigatePrevious(): Boolean {
    if (activeTrackIds.isEmpty() || currentTrackId == null) return false
    if (repeatMode == RepeatMode.One) return true
    return currentIndex > 0 || repeatMode == RepeatMode.All
}

internal fun PlaybackQueueSnapshot.canNavigateNext(): Boolean {
    if (activeTrackIds.isEmpty() || currentTrackId == null) return false
    if (repeatMode == RepeatMode.One) return true
    return currentIndex < activeTrackIds.lastIndex || repeatMode == RepeatMode.All
}

internal fun canDispatchQueueSwipe(
    swipeDirection: Int,
    canNavigatePrevious: Boolean,
    canNavigateNext: Boolean,
): Boolean = when {
    swipeDirection < 0 -> canNavigateNext
    swipeDirection > 0 -> canNavigatePrevious
    else -> false
}
