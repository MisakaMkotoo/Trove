package com.exodidio.tune.ui.components

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import com.exodidio.tune.R
import com.exodidio.tune.ui.theme.LocalTuneColors
import com.exodidio.tune.ui.theme.TuneRadii

private const val ArtworkCacheFraction = 8

internal fun artworkCacheMaxBytes(memoryClassMb: Int): Int =
    memoryClassMb * 1024 * 1024 / ArtworkCacheFraction

internal fun artworkCacheKey(absolutePath: String, targetPx: Int): String =
    "$absolutePath:$targetPx"

internal object ArtworkThumbnailCache {
    @Volatile private var backing: LruCache<String, ImageBitmap>? = null
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(memoryClassMb: Int) {
        if (backing == null) {
            synchronized(this) {
                if (backing == null) {
                    val maxBytes = artworkCacheMaxBytes(memoryClassMb)
                    backing = object : LruCache<String, ImageBitmap>(maxBytes) {
                        override fun sizeOf(key: String, value: ImageBitmap): Int {
                            return try {
                                value.asAndroidBitmap().allocationByteCount.coerceAtLeast(1)
                            } catch (_: Throwable) {
                                4 * 1024
                            }
                        }
                    }
                }
            }
        }
    }

    fun isInitialized(): Boolean = backing != null

    fun cacheKey(path: String, targetPx: Int): String = artworkCacheKey(path, targetPx)

    fun get(key: String): ImageBitmap? = try {
        backing?.get(key)
    } catch (_: Throwable) {
        null
    }

    fun put(key: String, value: ImageBitmap) {
        try {
            backing?.put(key, value)
        } catch (_: Throwable) {
            Unit
        } finally {
            inFlight.remove(key).let { }
        }
    }

    fun loadOrAwait(key: String, loader: suspend () -> ImageBitmap?): Deferred<ImageBitmap?> {
        return inFlight.computeIfAbsent(key) {
            scope.async {
                try {
                    val loaded = loader()
                    if (loaded != null) {
                        put(key, loaded)
                    } else {
                        inFlight.remove(key)
                    }
                    loaded
                } catch (t: Throwable) {
                    inFlight.remove(key)
                    throw t
                }
            }
        }
    }
}

// After: byte-budgeted cache initialised once from ActivityManager.memoryClass.
private fun ensureArtworkCache(context: Context) {
    if (ArtworkThumbnailCache.isInitialized()) return
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    ArtworkThumbnailCache.init(manager.memoryClass)
}

@Composable
internal fun rememberArtworkThumbnail(
    artworkPath: String?,
    targetPx: Int = 120,
): ImageBitmap? {
    if (artworkPath.isNullOrBlank()) return null
    val context = LocalContext.current
    ensureArtworkCache(context)
    val absolutePath = remember(artworkPath, context) {
        val file = File(artworkPath)
        if (file.isAbsolute) file.absolutePath else File(context.filesDir, artworkPath).absolutePath
    }
    val cacheKey = remember(absolutePath, targetPx) { ArtworkThumbnailCache.cacheKey(absolutePath, targetPx) }
    var bitmap by remember(cacheKey) { mutableStateOf(ArtworkThumbnailCache.get(cacheKey)) }
    LaunchedEffect(cacheKey) {
        val cached = ArtworkThumbnailCache.get(cacheKey)
        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }
        val loaded = runCatching {
            ArtworkThumbnailCache.loadOrAwait(cacheKey) {
                val file = File(absolutePath)
                if (!file.isFile) return@loadOrAwait null
                runCatching {
                    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
                    var sampleSize = 1
                    while (boundsOptions.outWidth / (sampleSize * 2) >= targetPx &&
                        boundsOptions.outHeight / (sampleSize * 2) >= targetPx
                    ) {
                        sampleSize *= 2
                    }
                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeFile(file.absolutePath, decodeOptions)?.asImageBitmap()
                }.getOrNull()
            }.await()
        }.getOrNull()
        if (loaded != null) {
            bitmap = loaded
        }
    }
    return bitmap
}

@Composable
fun TrackRow(
    title: String,
    artist: String,
    modifier: Modifier = Modifier,
    artworkPath: String? = null,
    artworkUrl: String? = null,
    contentPadding: PaddingValues = PaddingValues(start = 24.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
    artworkCornerRadius: Dp = TuneRadii.ListArtwork,
    titleStyle: TextStyle? = null,
    artistStyle: TextStyle? = null,
    moreButtonWidth: Dp = 48.dp,
    onClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = LocalTuneColors.current
    val reduceMotion = LocalReduceMotion.current
    val resolvedTitleStyle = titleStyle ?: MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    val resolvedArtistStyle = artistStyle ?: MaterialTheme.typography.bodySmall
    val bitmap = rememberArtworkThumbnail(artworkPath)
    val bitmapAlpha = rememberArtworkFadeAlpha(artworkPath, bitmap != null)
    val interactive = onClick != null || onLongClick != null
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressTint by animateColorAsState(
        targetValue = if (pressed && interactive && !reduceMotion) colors.textMain.copy(alpha = 0.05f) else Color.Transparent,
        animationSpec = if (reduceMotion) snap() else tween(140, easing = FastOutSlowInEasing),
        label = "track-row-press-tint",
    )
    val clickModifier = remember(onClick, onLongClick, interactionSource) {
        if (interactive) {
            Modifier.combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClick = onLongClick,
                interactionSource = interactionSource,
                indication = null,
            )
        } else {
            Modifier
        }
    }
    val handleMoreClick = remember(onMoreClick) {
        { onMoreClick?.invoke(); Unit }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(pressTint)
            .then(clickModifier)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. Artwork
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(artworkCornerRadius))
                .background(colors.glassElevated)
                .border(0.5.dp, colors.borderGlass, RoundedCornerShape(artworkCornerRadius)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                bitmap != null -> Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().alpha(bitmapAlpha),
                )
                !artworkUrl.isNullOrBlank() -> TuneRemoteImage(
                    url = artworkUrl,
                    sizePx = 200,
                    modifier = Modifier.matchParentSize(),
                    fallback = {
                        MaterialSymbol(
                            symbol = MaterialSymbols.MusicNote,
                            contentDescription = null,
                            size = 22.dp,
                            tint = colors.textMuted,
                        )
                    },
                )
                else -> MaterialSymbol(
                    symbol = MaterialSymbols.MusicNote,
                    contentDescription = null,
                    size = 22.dp,
                    tint = colors.textMuted,
                )
            }
        }

        // 2. Title & Artist Column
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = resolvedTitleStyle,
                color = colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist,
                style = resolvedArtistStyle,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        trailingContent?.invoke(this) ?: run {
            IconButton(
                onClick = handleMoreClick,
                modifier = Modifier.width(moreButtonWidth).height(48.dp),
            ) {
                MaterialSymbol(
                    symbol = MaterialSymbols.MoreVert,
                    contentDescription = stringResource(R.string.track_row_more_options),
                    size = 20.dp,
                    tint = colors.textMuted,
                )
            }
        }
    }
}

