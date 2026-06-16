package com.synclisten.shared.invite

import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeCodec {
    fun encode(content: String, size: Int): BitMatrix =
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
}
