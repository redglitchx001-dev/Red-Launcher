/*
 * Red Launcher Beta
 * Copyright (C) 2026 redglitchx001-dev and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3.0 or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.redlauncherbeta.feature.discord

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Extracts the player's face from a Minecraft skin file (64x64 or 128x128)
 * and renders it as a circular PNG — used as the Discord small image.
 */
object SkinFaceUtil {

    private const val TAG = "SkinFaceUtil"
    private const val FACE_UV_X = 8
    private const val FACE_UV_Y = 8
    private const val FACE_UV_SIZE = 8

    /**
     * @param skinFile local skin file for the current account
     * @param size output PNG edge size in pixels
     * @return PNG bytes, or null when the skin is missing/invalid
     */
    fun extractFacePng(skinFile: File, size: Int = 160): ByteArray? {
        if (!skinFile.exists() || !skinFile.canRead()) {
            Log.w(TAG, "Skin file not found: ${skinFile.path}")
            return null
        }

        val skin = runCatching {
            android.graphics.BitmapFactory.decodeFile(skinFile.absolutePath)
        }.getOrNull() ?: run {
            Log.w(TAG, "Could not decode skin file")
            return null
        }

        return runCatching {
            val srcLeft = FACE_UV_X
            val srcTop = FACE_UV_Y
            val srcWidth = FACE_UV_SIZE
            val srcHeight = FACE_UV_SIZE

            if (srcLeft + srcWidth > skin.width || srcTop + srcHeight > skin.height) {
                Log.w(TAG, "Skin too small for face UVs: ${skin.width}x${skin.height}")
                null
            } else {
                // Pixel-perfect nearest-neighbour upscale (classic MC look)
                val face = Bitmap.createBitmap(
                    srcWidth, srcHeight, Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(face)
                canvas.drawBitmap(
                    skin,
                    Rect(srcLeft, srcTop, srcLeft + srcWidth, srcTop + srcHeight),
                    Rect(0, 0, srcWidth, srcHeight),
                    null
                )

                val scaled = Bitmap.createScaledBitmap(
                    face, size, size, false
                )

                // Circular crop: draw an opaque circle, then keep the face
                // only where the circle is (SRC_IN).
                val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val outCanvas = Canvas(output)
                val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                }
                outCanvas.drawCircle(size / 2f, size / 2f, size / 2f, circlePaint)
                val cropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
                }
                outCanvas.drawBitmap(scaled, 0f, 0f, cropPaint)

                val stream = ByteArrayOutputStream()
                output.compress(Bitmap.CompressFormat.PNG, 100, stream)

                if (skin !== scaled) skin.recycle()
                if (face !== scaled) face.recycle()
                scaled.recycle()
                output.recycle()

                stream.toByteArray()
            }
        }.getOrElse { e ->
            Log.e(TAG, "Failed to extract skin face", e)
            null
        }
    }
}
