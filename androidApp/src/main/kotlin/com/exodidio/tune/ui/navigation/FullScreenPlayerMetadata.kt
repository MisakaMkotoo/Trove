package com.exodidio.tune.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import com.exodidio.tune.R
import com.exodidio.tune.player.ArtworkCrossfadeTransition
import com.exodidio.tune.player.PlaybackItem
import com.exodidio.tune.player.PlaybackQueueSnapshot
import com.exodidio.tune.sync.LibraryTrack
import com.exodidio.tune.ui.components.TuneIconButton
import com.exodidio.tune.ui.components.TuneIconButtonVariant
import com.exodidio.tune.ui.components.TuneMarqueeText
import com.exodidio.tune.ui.components.TunePillButton
import com.exodidio.tune.ui.components.TunePillButtonVariant
import com.exodidio.tune.ui.components.MaterialSymbol
import com.exodidio.tune.ui.components.MaterialSymbols
import com.exodidio.tune.ui.components.TrackContextBottomSheetRequest
import com.exodidio.tune.ui.components.TrackContextMenu
import com.exodidio.tune.ui.components.TrackContextMenuActions
import com.exodidio.tune.ui.components.TrackInfoValue
import com.exodidio.tune.ui.theme.LocalTuneColors

internal const val FullScreenPlayerMetadataSwipeTestTag = "full_screen_player_metadata_swipe_target"

@Composable
internal fun FullScreenPlayerMetadataTransition(
    item: PlaybackItem,
    crossfade: ArtworkCrossfadeTransition?,
    displayedHorizontalSwipeOffset: Float,
    hazeState: HazeState?,
    compact: Boolean,
    isFavorite: Boolean,
    onFavoriteToggle: (String, Boolean) -> Unit,
    isNeteaseTrack: Boolean = false,
    isNeteaseLiked: Boolean = false,
    onNeteaseLikeToggle: (String, Boolean) -> Unit = { _, _ -> },
    contextTrack: LibraryTrack?,
    contextMenuExpanded: Boolean,
    onContextMenuOpen: () -> Unit,
    onContextMenuDismiss: () -> Unit,
    playbackQueue: PlaybackQueueSnapshot,
    onTrackPlayNext: (String) -> Unit,
    onTrackAddToQueue: (String) -> Unit,
    moodRadioEligibleTrackIds: Set<String>,
    onStartMoodRadio: (String) -> Unit,
    onTrackGoToAlbum: (String) -> Unit,
    onTrackGoToArtist: (String) -> Unit,
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit,
    onCloseFullscreenThen: ((() -> Unit) -> Unit),
    onMoreClick: () -> Unit = {},
) {
    androidx.compose.animation.AnimatedContent(
        targetState = item,
        transitionSpec = {
            if (crossfade != null) {
                (slideInHorizontally(tween(200, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(200, easing = FastOutSlowInEasing))) togetherWith
                    (slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(tween(160, easing = FastOutSlowInEasing)))
            } else EnterTransition.None togetherWith ExitTransition.None
        },
        label = "full-screen-player-metadata-crossfade",
    ) { animatedItem ->
        FullScreenPlayerMetadata(
            animatedItem, displayedHorizontalSwipeOffset, hazeState, compact,
            isFavorite, onFavoriteToggle, isNeteaseTrack, isNeteaseLiked, onNeteaseLikeToggle,
            contextTrack, contextMenuExpanded, onContextMenuOpen, onContextMenuDismiss, playbackQueue,
            onTrackPlayNext, onTrackAddToQueue, moodRadioEligibleTrackIds, onStartMoodRadio, onTrackGoToAlbum, onTrackGoToArtist,
            onTrackContextBottomSheet, onCloseFullscreenThen, onMoreClick,
        )
    }
}

@Composable
private fun FullScreenPlayerMetadata(
    item: PlaybackItem,
    displayedHorizontalSwipeOffset: Float,
    hazeState: HazeState?,
    compact: Boolean,
    isFavorite: Boolean,
    onFavoriteToggle: (String, Boolean) -> Unit,
    isNeteaseTrack: Boolean,
    isNeteaseLiked: Boolean,
    onNeteaseLikeToggle: (String, Boolean) -> Unit,
    contextTrack: LibraryTrack?,
    contextMenuExpanded: Boolean,
    onContextMenuOpen: () -> Unit,
    onContextMenuDismiss: () -> Unit,
    playbackQueue: PlaybackQueueSnapshot,
    onTrackPlayNext: (String) -> Unit,
    onTrackAddToQueue: (String) -> Unit,
    moodRadioEligibleTrackIds: Set<String>,
    onStartMoodRadio: (String) -> Unit,
    onTrackGoToAlbum: (String) -> Unit,
    onTrackGoToArtist: (String) -> Unit,
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit,
    onCloseFullscreenThen: ((() -> Unit) -> Unit),
    onMoreClick: () -> Unit,
) {
    val colors = LocalTuneColors.current
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    val libraryScale = remember(item.trackId) { Animatable(1f) }
    var previousFavorite by remember(item.trackId) { mutableStateOf(isFavorite) }
    LaunchedEffect(item.trackId, isFavorite) {
        val wasAdded = isFavorite && !previousFavorite
        previousFavorite = isFavorite
        if (wasAdded) {
            libraryScale.snapTo(1f)
            libraryScale.animateTo(1.14f, tween(120, easing = FastOutSlowInEasing))
            libraryScale.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
        } else if (!isFavorite) libraryScale.snapTo(1f)
    }
    val neteaseLikeScale = remember(item.trackId) { Animatable(1f) }
    var previousNeteaseLiked by remember(item.trackId) { mutableStateOf(isNeteaseLiked) }
    LaunchedEffect(item.trackId, isNeteaseLiked) {
        val wasAdded = isNeteaseLiked && !previousNeteaseLiked
        previousNeteaseLiked = isNeteaseLiked
        if (wasAdded) {
            neteaseLikeScale.snapTo(1f)
            neteaseLikeScale.animateTo(1.14f, tween(120, easing = FastOutSlowInEasing))
            neteaseLikeScale.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
        } else if (!isNeteaseLiked) neteaseLikeScale.snapTo(1f)
    }
    val libraryLabel = stringResource(R.string.player_library)
    val neteaseLikeLabel = stringResource(R.string.player_netease_like)
    val moreLabel = stringResource(R.string.player_more)
    Row(
        modifier = if (compact) Modifier.fillMaxWidth().padding(end = 12.dp) else Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clipToBounds().graphicsLayer { translationX = displayedHorizontalSwipeOffset }
            .semantics { testTag = FullScreenPlayerMetadataSwipeTestTag }) {
            TuneMarqueeText(
                item.title,
                colors.onPrimary,
                if (compact) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                // 苹果播放页标题 24sp、艺人 20sp。
                else MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
            )
            TuneMarqueeText(
                item.artist,
                colors.foregroundSubtle,
                if (compact) MaterialTheme.typography.bodySmall
                else MaterialTheme.typography.bodyMedium.copy(fontSize = 20.sp, lineHeight = 26.sp),
            )
        }
        Spacer(Modifier.width(4.dp))
        val buttonSize = if (compact) 36.dp else 44.dp
        val iconSize = if (compact) 20.dp else 24.dp
        val heartSize = if (compact) 21.dp else 25.dp
        Box(
            modifier = Modifier
                .size(buttonSize)
                .graphicsLayer { scaleX = libraryScale.value; scaleY = libraryScale.value }
                .semantics { contentDescription = libraryLabel }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) {
                    if (!isFavorite) hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                    onFavoriteToggle(item.trackId, !isFavorite)
                },
            contentAlignment = Alignment.Center,
        ) {
            MaterialSymbol(
                symbol = MaterialSymbols.Library,
                tint = colors.onPrimary,
                size = iconSize,
                filled = isFavorite,
            )
        }
        if (isNeteaseTrack) {
            Spacer(Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .size(buttonSize)
                    .graphicsLayer { scaleX = neteaseLikeScale.value; scaleY = neteaseLikeScale.value }
                    .semantics { contentDescription = neteaseLikeLabel }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                    ) {
                        if (!isNeteaseLiked) hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        onNeteaseLikeToggle(item.trackId, !isNeteaseLiked)
                    },
                contentAlignment = Alignment.Center,
            ) {
                MaterialSymbol(
                    symbol = MaterialSymbols.Favorite,
                    tint = if (isNeteaseLiked) androidx.compose.ui.graphics.Color(0xFFFF3B5C) else colors.onPrimary,
                    size = heartSize,
                    filled = isNeteaseLiked,
                )
            }
        }
        Spacer(Modifier.width(2.dp))
        Box(
            modifier = Modifier
                .size(buttonSize)
                .semantics { contentDescription = moreLabel }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onMoreClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            MaterialSymbol(
                symbol = MaterialSymbols.MoreVert,
                tint = colors.onPrimary,
                size = iconSize,
            )
        }
    }
}

@Composable
internal fun FullScreenQualityDialog(labelRes: Int, symbol: String, details: List<TrackInfoValue>, onDismiss: () -> Unit) {
    val colors = LocalTuneColors.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true, usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).background(colors.card, RoundedCornerShape(24.dp)), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MaterialSymbol(symbol, null, size = 32.dp, tint = colors.textMain)
                    Text(stringResource(labelRes), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = colors.textMain)
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    details.forEach { detail -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(detail.labelRes), style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                        Text(detail.value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = colors.textMain)
                    } }
                }
            }
            TunePillButton(stringResource(R.string.ok), onDismiss, TunePillButtonVariant.Primary, Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 16.dp))
        }
    }
}
