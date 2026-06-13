package com.synclisten.app.invite

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeCodec {
    fun encode(content: String, size: Int): BitMatrix =
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)

    fun bitmap(content: String, size: Int): Bitmap {
        val matrix = encode(content, size)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until size) {
                for (y in 0 until size) {
                    setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
        }
    }
}
