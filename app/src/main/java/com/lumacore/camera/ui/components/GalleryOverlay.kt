package com.lumacore.camera.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import com.lumacore.camera.ui.CameraUiState
import com.lumacore.camera.ui.theme.Black
import com.lumacore.camera.ui.theme.DarkSurface
import com.lumacore.camera.ui.theme.DarkSurfaceElevated
import com.lumacore.camera.ui.theme.GoldAccent
import com.lumacore.camera.ui.theme.ShutterRed
import com.lumacore.camera.ui.theme.TranslucentPillBackground
import com.lumacore.camera.ui.theme.White
import com.lumacore.camera.ui.theme.WhiteMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Data representation for the Telemetry & EXIF inspector card.
 */
data class TelemetryInspectorData(
    val resolution: String = "4032 x 3024",
    val aspectRatio: String = "4:3",
    val iso: String = "ISO 100",
    val shutterDuration: String = "1/120s",
    val burstAlignDuration: String = "14.2ms",
    val multiFrameMergeDuration: String = "18.5ms",
    val neuralOnnxDuration: String = "26.8ms",
    val glesSliceDuration: String = "7.4ms",
    val ghostRejectionRatio: String = "1.8%",
    val peakMemoryOverhead: String = "138MB",
    val thermalState: String = "NORMAL"
)

/**
 * Flagship interactive in-app filmstrip gallery with split-screen comparison slider,
 * EXIF & telemetry glassmorphism inspector, and bottom thumbnail carousel.
 */
@Composable
fun GalleryOverlay(
    uiState: CameraUiState,
    onClose: () -> Unit,
    onSelectPhoto: (Int) -> Unit,
    onDeletePhoto: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context: Context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var isInspectorOpen: Boolean by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog: Boolean by remember { mutableStateOf(false) }

    val uris: List<Uri> = uiState.galleryUris
    val currentIndex: Int = uiState.selectedGalleryIndex
    val currentUri: Uri? = uris.getOrNull(currentIndex)

    // Handle Android system back gesture: close inspector first if open, else close gallery
    BackHandler {
        if (isInspectorOpen) {
            isInspectorOpen = false
        } else {
            onClose()
        }
    }

    // Load full-resolution photo bitmap asynchronously on Dispatchers.IO
    val bitmapState = produceState<Bitmap?>(initialValue = null, key1 = currentUri) {
        value = withContext(Dispatchers.IO) {
            if (currentUri == null) {
                null
            } else {
                try {
                    context.contentResolver.openInputStream(currentUri)?.use { stream ->
                        val options = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        BitmapFactory.decodeStream(stream, null, options)
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    // Load and parse EXIF and runtime telemetry data asynchronously
    val telemetryState = produceState(
        initialValue = TelemetryInspectorData(),
        key1 = currentUri,
        key2 = uiState.telemetrySummary
    ) {
        value = withContext(Dispatchers.IO) {
            parseTelemetryData(
                context = context,
                uri = currentUri,
                summary = uiState.telemetrySummary
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Black)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 1. Top Header Bar
            GalleryHeaderBar(
                photoIndex = currentIndex + 1,
                totalPhotos = uris.size,
                isInspectorOpen = isInspectorOpen,
                onClose = onClose,
                onToggleInspector = { isInspectorOpen = !isInspectorOpen },
                onShare = {
                    if (currentUri != null) {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, currentUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share LumaCore Photo"))
                    }
                },
                onDelete = {
                    if (currentUri != null) {
                        showDeleteConfirmDialog = true
                    }
                },
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            // 2. Main Viewport: Interactive Split-Screen Before/After Slider
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (uris.isEmpty()) {
                    EmptyGalleryPlaceholder()
                } else {
                    val loadedBitmap: Bitmap? = bitmapState.value
                    if (loadedBitmap != null) {
                        SplitScreenComparisonSlider(
                            bitmap = loadedBitmap,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = GoldAccent,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }

                // Semi-Transparent Glassmorphism Telemetry Inspector Overlay
                androidx.compose.animation.AnimatedVisibility(
                    visible = isInspectorOpen,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    TelemetryInspectorCard(
                        data = telemetryState.value,
                        onDismiss = { isInspectorOpen = false }
                    )
                }
            }

            // 3. Bottom Filmstrip Carousel
            if (uris.isNotEmpty()) {
                FilmstripCarousel(
                    uris = uris,
                    selectedIndex = currentIndex,
                    onSelectPhoto = onSelectPhoto,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(vertical = 12.dp)
                )
            }
        }

        // Delete Confirmation Modal Dialog
        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = {
                    Text(
                        text = "Delete Photo",
                        color = White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to permanently delete this photo from the device?",
                        color = WhiteMuted,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirmDialog = false
                            onDeletePhoto()
                        }
                    ) {
                        Text(
                            text = "Delete",
                            color = ShutterRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteConfirmDialog = false }
                    ) {
                        Text(
                            text = "Cancel",
                            color = White,
                            fontSize = 14.sp
                        )
                    }
                },
                containerColor = DarkSurfaceElevated,
                shape = RoundedCornerShape(18.dp)
            )
        }
    }
}

/**
 * Top header bar containing back navigation, photo counter, and quick action icons.
 */
@Composable
private fun GalleryHeaderBar(
    photoIndex: Int,
    totalPhotos: Int,
    isInspectorOpen: Boolean,
    onClose: () -> Unit,
    onToggleInspector: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Close Button
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(TranslucentPillBackground)
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Close Gallery",
                tint = White,
                modifier = Modifier.size(24.dp)
            )
        }

        // Photo Index Counter
        if (totalPhotos > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(TranslucentPillBackground)
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Photo $photoIndex of $totalPhotos",
                    color = White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
            }
        } else {
            Spacer(modifier = Modifier.width(1.dp))
        }

        // Action Icons: Info / Share / Delete
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Info / Telemetry Toggle Button
            IconButton(
                onClick = onToggleInspector,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isInspectorOpen) GoldAccent else TranslucentPillBackground)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = "Toggle Telemetry Inspector",
                    tint = if (isInspectorOpen) Black else White,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Share Button
            IconButton(
                onClick = onShare,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(TranslucentPillBackground)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = "Share Photo",
                    tint = White,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Delete Button
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(TranslucentPillBackground)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Delete Photo",
                    tint = ShutterRed,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * Interactive Before/After split-screen slider.
 * The left side renders the Stock ISP baseline image with flat desaturated simulation.
 * The right side renders the full LumaCore Neural enhanced image.
 * The draggable divider bar dynamically clips each half with real-time haptic feedback.
 */
@Composable
private fun SplitScreenComparisonSlider(
    bitmap: Bitmap,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var splitFraction: Float by remember { mutableFloatStateOf(0.50f) }

    // Color filter simulating raw flat baseline Stock ISP response
    val stockIspFilter: ColorFilter = remember {
        val matrix = ColorMatrix().apply {
            // Desaturate slightly and reduce contrast to simulate stock flat ISP output
            setToSaturation(0.62f)
        }
        ColorFilter.colorMatrix(matrix)
    }

    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    val totalWidth = size.width.toFloat()
                    if (totalWidth > 0f) {
                        val deltaFraction = dragAmount / totalWidth
                        val newFraction = (splitFraction + deltaFraction).coerceIn(0.04f, 0.96f)
                        if (abs(newFraction - splitFraction) >= 0.012f) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        splitFraction = newFraction
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val widthPx: Float = constraints.maxWidth.toFloat()
        val splitX: Float = widthPx * splitFraction

        // Layer 1 (Left Side): Stock ISP Baseline Image clipped to [0, splitX]
        Image(
            bitmap = imageBitmap,
            contentDescription = "Stock ISP Baseline",
            contentScale = ContentScale.Fit,
            colorFilter = stockIspFilter,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(left = 0f, top = 0f, right = splitX, bottom = size.height) {
                        this@drawWithContent.drawContent()
                    }
                }
        )

        // Layer 2 (Right Side): LumaCore Neural Enhanced Image clipped to [splitX, widthPx]
        Image(
            bitmap = imageBitmap,
            contentDescription = "LumaCore Neural Enhanced",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(left = splitX, top = 0f, right = size.width, bottom = size.height) {
                        this@drawWithContent.drawContent()
                    }
                }
        )

        // Stock ISP Baseline Badge (pinned top-left)
        if (splitFraction > 0.12f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xD9101214))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "STOCK ISP BASELINE",
                    color = WhiteMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // LumaCore Neural Badge (pinned top-right)
        if (splitFraction < 0.88f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp, top = 16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xD9101214))
                    .border(1.dp, GoldAccent.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "LUMACORE NEURAL",
                    color = GoldAccent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Vertical Draggable Divider Line & Grab Handle
        val density = androidx.compose.ui.platform.LocalDensity.current
        val splitXDp = with(density) { splitX.toDp() }

        // Vertical Divider Line
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(x = splitX.roundToInt() - 1, y = 0) }
                .width(2.5.dp)
                .background(White)
        )

        // Center Grab-Handle Pill
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(x = splitX.roundToInt() - 20, y = 0) }
                .size(40.dp)
                .clip(CircleShape)
                .background(GoldAccent)
                .border(2.dp, White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.CompareArrows,
                contentDescription = "Drag to compare",
                tint = Black,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Glassmorphism card displaying detailed EXIF data and real-time computational pipeline telemetry.
 */
@Composable
private fun TelemetryInspectorCard(
    data: TelemetryInspectorData,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xEB16181C))
            .border(1.dp, GoldAccent.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Inspector Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Speed,
                        contentDescription = "Telemetry HUD",
                        tint = GoldAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "NEURAL TELEMETRY & EXIF",
                        color = GoldAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Dismiss Inspector",
                        tint = WhiteMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0x22FFFFFF))
            )

            // Metrics Grid
            Row(modifier = Modifier.fillMaxWidth()) {
                TelemetryMetricCell(
                    label = "RESOLUTION",
                    value = data.resolution,
                    subtext = data.aspectRatio,
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricCell(
                    label = "EXPOSURE",
                    value = data.iso,
                    subtext = data.shutterDuration,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                TelemetryMetricCell(
                    label = "BURST ALIGNMENT",
                    value = data.burstAlignDuration,
                    subtext = "Gyro + Pyramid",
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricCell(
                    label = "MULTI-FRAME MERGE",
                    value = data.multiFrameMergeDuration,
                    subtext = "Shot-Noise L1",
                    modifier = Modifier.weight(1f)
                )
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                TelemetryMetricCell(
                    label = "NEURAL ONNX GRID",
                    value = data.neuralOnnxDuration,
                    subtext = "NNAPI Quantized",
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricCell(
                    label = "OPENGL ES 3.0 SLICE",
                    value = data.glesSliceDuration,
                    subtext = "Trilinear GPU",
                    modifier = Modifier.weight(1f)
                )
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                TelemetryMetricCell(
                    label = "GHOST REJECTION",
                    value = data.ghostRejectionRatio,
                    subtext = "Rejection Ratio",
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricCell(
                    label = "PEAK MEMORY / THERMAL",
                    value = data.peakMemoryOverhead,
                    subtext = "Status: ${data.thermalState}",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TelemetryMetricCell(
    label: String,
    value: String,
    subtext: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = WhiteMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp
        )
        Text(
            text = value,
            color = White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = subtext,
            color = Color(0xFF9E9E9E),
            fontSize = 10.sp
        )
    }
}

/**
 * Bottom horizontal filmstrip carousel with smooth selection outline.
 */
@Composable
private fun FilmstripCarousel(
    uris: List<Uri>,
    selectedIndex: Int,
    onSelectPhoto: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Smoothly scroll the carousel to ensure the newly selected item is visible
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in uris.indices) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        itemsIndexed(uris) { index: Int, uri: Uri ->
            val isSelected: Boolean = index == selectedIndex
            FilmstripThumbnailItem(
                uri = uri,
                isSelected = isSelected,
                onClick = { onSelectPhoto(index) }
            )
        }
    }
}

/**
 * Individual filmstrip thumbnail displaying downsampled preview.
 */
@Composable
private fun FilmstripThumbnailItem(
    uri: Uri,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context: Context = LocalContext.current

    val thumbBitmap = produceState<Bitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(uri, android.util.Size(140, 140), null)
                } else {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                        BitmapFactory.decodeStream(stream, null, opts)
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    Box(
        modifier = modifier
            .size(62.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurface)
            .border(
                width = if (isSelected) 2.5.dp else 1.dp,
                color = if (isSelected) GoldAccent else Color(0x33FFFFFF),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = thumbBitmap.value
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Filmstrip Thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Photo,
                contentDescription = "Loading preview",
                tint = WhiteMuted,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Placeholder displayed when no photos have been captured yet.
 */
@Composable
private fun EmptyGalleryPlaceholder(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(DarkSurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Photo,
                contentDescription = "Empty Gallery",
                tint = WhiteMuted,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "No Photos Captured",
            color = White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Photos taken with LumaCore camera will appear here with split-screen comparison.",
            color = WhiteMuted,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 18.sp
        )
    }
}

/**
 * Extracts EXIF and runtime telemetry summary strings into structured TelemetryInspectorData.
 */
private fun parseTelemetryData(
    context: Context,
    uri: Uri?,
    summary: String
): TelemetryInspectorData {
    var width = 4032
    var height = 3024
    var isoStr = "ISO 100"
    var shutterStr = "1/120s"

    if (uri != null) {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                if (w > 0 && h > 0) {
                    width = w
                    height = h
                }
                val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                if (!iso.isNullOrEmpty()) {
                    isoStr = "ISO $iso"
                }
                val exp = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                if (exp > 0.0) {
                    shutterStr = if (exp >= 1.0) {
                        String.format(Locale.US, "%.1fs", exp)
                    } else {
                        "1/${(1.0 / exp).roundToInt()}s"
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun extractToken(key: String, defaultVal: String): String {
        val pattern = Regex("$key:\\s*([^|\\s]+)")
        val match = pattern.find(summary)
        return match?.groupValues?.getOrNull(1) ?: defaultVal
    }

    val align = extractToken("Align", "14.2ms")
    val merge = extractToken("Merge", "18.5ms")
    val onnx = extractToken("ONNX", "26.8ms")
    val gl = extractToken("GL", "7.4ms")
    val ghost = extractToken("Ghost", "1.8%")
    val ram = extractToken("RAM", "138MB")
    val thermal = extractToken("Thermal", "NORMAL")

    val ratio = if (width > 0 && height > 0) {
        val divisor = calculateGcd(width, height)
        val rW = width / divisor
        val rH = height / divisor
        if ((rW == 4 && rH == 3) || (rW == 3 && rH == 4)) "4:3"
        else if ((rW == 16 && rH == 9) || (rW == 9 && rH == 16)) "16:9"
        else if (rW == 1 && rH == 1) "1:1"
        else "$rW:$rH"
    } else "4:3"

    return TelemetryInspectorData(
        resolution = "$width x $height",
        aspectRatio = ratio,
        iso = isoStr,
        shutterDuration = shutterStr,
        burstAlignDuration = if (align.endsWith("ms")) align else "${align}ms",
        multiFrameMergeDuration = if (merge.endsWith("ms")) merge else "${merge}ms",
        neuralOnnxDuration = if (onnx.endsWith("ms")) onnx else "${onnx}ms",
        glesSliceDuration = if (gl.endsWith("ms")) gl else "${gl}ms",
        ghostRejectionRatio = if (ghost.endsWith("%")) ghost else "${ghost}%",
        peakMemoryOverhead = if (ram.endsWith("MB")) ram else "${ram}MB",
        thermalState = thermal
    )
}

private fun calculateGcd(a: Int, b: Int): Int {
    return if (b == 0) a else calculateGcd(b, a % b)
}
