package com.lumacore.camera.core.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles persistent storage of captured images into the system MediaStore
 * under the DCIM/LumaCore directory with full EXIF metadata.
 */
object MediaSaver {

    private const val TAG: String = "MediaSaver"
    private const val DIRECTORY_NAME: String = "DCIM/LumaCore"

    /**
     * Saves raw JPEG bytes to MediaStore with EXIF tags.
     */
    suspend fun saveJpeg(
        context: Context,
        jpegBytes: ByteArray,
        orientationDegrees: Int = 0,
        iso: Int? = null,
        exposureTimeSeconds: Double? = null
    ): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val fileName = "LUMA_$timeStamp.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.RELATIVE_PATH, DIRECTORY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri == null) {
            Log.e(TAG, "Failed to create MediaStore entry in $DIRECTORY_NAME")
            return@withContext null
        }

        try {
            // Write JPEG binary stream
            resolver.openOutputStream(uri)?.use { outputStream: OutputStream ->
                outputStream.write(jpegBytes)
                outputStream.flush()
            } ?: run {
                Log.e(TAG, "Failed to open output stream for uri: $uri")
                return@withContext null
            }

            // Write EXIF tags into written JPEG file descriptor
            writeExifMetadata(
                context = context,
                uri = uri,
                orientationDegrees = orientationDegrees,
                iso = iso,
                exposureTimeSeconds = exposureTimeSeconds
            )

            // Mark write operation completed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            Log.i(TAG, "Image saved successfully: $uri ($fileName)")
            return@withContext uri
        } catch (e: Exception) {
            Log.e(TAG, "Exception saving JPEG to MediaStore", e)
            try {
                resolver.delete(uri, null, null)
            } catch (delEx: Exception) {
                Log.w(TAG, "Failed to cleanup incomplete MediaStore entry", delEx)
            }
            return@withContext null
        }
    }

    /**
     * Saves a [Bitmap] to MediaStore.
     */
    suspend fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        orientationDegrees: Int = 0,
        iso: Int? = null,
        exposureTimeSeconds: Double? = null
    ): Uri? = withContext(Dispatchers.IO) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        val jpegBytes = stream.toByteArray()
        return@withContext saveJpeg(
            context = context,
            jpegBytes = jpegBytes,
            orientationDegrees = orientationDegrees,
            iso = iso,
            exposureTimeSeconds = exposureTimeSeconds
        )
    }

    /**
     * Converts and saves an [Image] (JPEG, YUV_420_888, or RAW_SENSOR) to MediaStore.
     */
    suspend fun saveImage(
        context: Context,
        image: Image,
        orientationDegrees: Int = 0,
        iso: Int? = null,
        exposureTimeSeconds: Double? = null
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val jpegBytes: ByteArray = when (image.format) {
                ImageFormat.JPEG -> {
                    val buffer: ByteBuffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    bytes
                }
                ImageFormat.YUV_420_888 -> {
                    convertYuv420ToJpegBytes(image)
                }
                ImageFormat.RAW_SENSOR -> {
                    convertRawToJpegBytes(image)
                }
                else -> {
                    Log.w(TAG, "Unsupported image format: ${image.format}, attempting fallback extraction")
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    bytes
                }
            }

            return@withContext saveJpeg(
                context = context,
                jpegBytes = jpegBytes,
                orientationDegrees = orientationDegrees,
                iso = iso,
                exposureTimeSeconds = exposureTimeSeconds
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert and save image format ${image.format}", e)
            return@withContext null
        }
    }

    /**
     * Converts YUV_420_888 camera frame to standard JPEG bytes via NV21 intermediate format.
     */
    fun convertYuv420ToJpegBytes(image: Image): ByteArray {
        val width: Int = image.width
        val height: Int = image.height
        val ySize: Int = width * height
        val uvSize: Int = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        var pos = 0
        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
            pos = ySize
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
        }

        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val uvHeight = height / 2
        val uvWidth = width / 2

        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val vIndex = row * uvRowStride + col * uvPixelStride
                val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                nv21[pos++] = vBuffer.get(vIndex)
                nv21[pos++] = uBuffer.get(uIndex)
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val outStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 95, outStream)
        return outStream.toByteArray()
    }

    /**
     * Converts RAW_SENSOR pattern buffer into preview-compatible JPEG representation.
     */
    private fun convertRawToJpegBytes(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val buffer = image.planes[0].buffer

        // Generate downsampled grayscale/RGB bitmap from raw 16-bit / 10-bit bayer data
        val sampleWidth = width / 2
        val sampleHeight = height / 2
        val pixels = IntArray(sampleWidth * sampleHeight)

        val rowStride = image.planes[0].rowStride
        val pixelStride = image.planes[0].pixelStride

        for (y in 0 until sampleHeight) {
            val rowOffset = (y * 2) * rowStride
            for (x in 0 until sampleWidth) {
                val offset = rowOffset + (x * 2) * pixelStride
                if (offset + 1 < buffer.capacity()) {
                    val low = buffer.get(offset).toInt() and 0xFF
                    val high = buffer.get(offset + 1).toInt() and 0xFF
                    val raw16 = (high shl 8) or low
                    val gray = (raw16 shr 8).coerceIn(0, 255)
                    pixels[y * sampleWidth + x] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                }
            }
        }

        val bitmap = Bitmap.createBitmap(pixels, sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    private fun writeExifMetadata(
        context: Context,
        uri: Uri,
        orientationDegrees: Int,
        iso: Int?,
        exposureTimeSeconds: Double?
    ): Unit {
        try {
            val pfd: ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(uri, "rw")
            pfd?.use { descriptor ->
                val exif = ExifInterface(descriptor.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_MAKE, "LumaCore")
                exif.setAttribute(ExifInterface.TAG_MODEL, "Neural Camera")
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "LumaCore Neural Pipeline 1.0")

                val dateString = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateString)
                exif.setAttribute(ExifInterface.TAG_DATETIME, dateString)

                exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientationToExifTag(orientationDegrees))

                if (iso != null && iso > 0) {
                    exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, iso.toString())
                }

                if (exposureTimeSeconds != null && exposureTimeSeconds > 0.0) {
                    exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, String.format(Locale.US, "%.6f", exposureTimeSeconds))
                }

                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Non-fatal: could not write complete EXIF metadata for $uri", e)
        }
    }

    private fun orientationToExifTag(degrees: Int): String {
        return when ((degrees % 360 + 360) % 360) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90.toString()
            180 -> ExifInterface.ORIENTATION_ROTATE_180.toString()
            270 -> ExifInterface.ORIENTATION_ROTATE_270.toString()
            else -> ExifInterface.ORIENTATION_NORMAL.toString()
        }
    }
}
