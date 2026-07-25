package com.yubegreen.luonnotar.ui.visual

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import androidx.annotation.DrawableRes
import java.io.File
import kotlin.math.max

object BackgroundImageStore {
    private const val FILE_NAME = "custom-background.jpg"
    private const val MAX_EDGE_PX = 2560

    fun imageFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun hasImage(context: Context): Boolean {
        val target = imageFile(context)
        val backup = File(target.parentFile, "$FILE_NAME.bak")
        if ((!target.isFile || target.length() <= 0L) && backup.isFile) {
            target.delete()
            backup.renameTo(target)
        }
        return target.isFile && target.length() > 0L
    }

    fun decodeFromUri(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("无法读取所选图片")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别所选图片" }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE_PX) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        } ?: error("图片解码失败")
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val normalized = rotateForOrientation(decoded, orientation)
        if (normalized !== decoded) decoded.recycle()
        return normalized
    }

    fun saveBitmap(context: Context, bitmap: Bitmap) {
        val target = imageFile(context)
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        val backup = File(target.parentFile, "$FILE_NAME.bak")
        try {
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            temp.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    "背景图片保存失败"
                }
            }
            if (backup.exists() && !backup.delete()) error("无法清理背景备份")
            if (target.exists() && !target.renameTo(backup)) error("无法备份旧背景")
            if (!temp.renameTo(target)) {
                runCatching {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }.getOrElse { error ->
                    target.delete()
                    if (backup.exists()) backup.renameTo(target)
                    throw error
                }
            }
            backup.delete()
        } finally {
            if (temp.exists()) temp.delete()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
        }
    }

    fun decodeCustomForDisplay(context: Context, width: Int, height: Int): Bitmap? {
        if (!hasImage(context)) return null
        val file = imageFile(context)
        return decodeFileForDisplay(file, width, height)
    }

    fun decodeResourceForDisplay(
        context: Context,
        @DrawableRes resourceId: Int,
        width: Int,
        height: Int
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, resourceId, bounds)
        val sample = sampleFor(bounds.outWidth, bounds.outHeight, width, height)
        return BitmapFactory.decodeResource(
            context.resources,
            resourceId,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    private fun decodeFileForDisplay(file: File, width: Int, height: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, width, height)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    private fun sampleFor(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): Int {
        val targetEdge = max(width, height).coerceAtLeast(1080)
        var sample = 1
        while (max(sourceWidth, sourceHeight) / (sample * 2) >= targetEdge) sample *= 2
        return sample
    }

    private fun rotateForOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }
}
