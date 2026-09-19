package com.printtool

import com.lowagie.text.Document
import com.lowagie.text.Rectangle
import com.lowagie.text.pdf.BaseFont
import com.lowagie.text.pdf.PdfContentByte
import com.lowagie.text.pdf.PdfWriter
import com.lowagie.text.Image as PdfImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object PdfGenerator {
    private const val MM_TO_PT = 2.83465f
    fun createPdf(
        items: List<ProductItem>, 
        outputFile: File, 
        paperWidthMm: Float = 80f,
        paperHeightMm: Float = 130f,
        labelsPerPage: Int = 3,
        onProgress: ((Int, Int) -> Unit)? = null
    ) {
        val paperWidth = paperWidthMm * MM_TO_PT
        val paperHeight = paperHeightMm * MM_TO_PT
        val document = Document(Rectangle(paperWidth, paperHeight))
        document.setMargins(0f, 0f, 0f, 0f)
        val writer = PdfWriter.getInstance(document, FileOutputStream(outputFile))
        document.open()
        
        val cb = writer.directContent
        
        // Try multiple fonts if available
        val fontPaths = listOf(
            "C:/Windows/Fonts/msyh.ttc,0",
            "C:/Windows/Fonts/simsun.ttc,0",
            "C:/Windows/Fonts/simhei.ttf"
        )
        
        var baseFont: BaseFont? = null
        for (path in fontPaths) {
            try {
                baseFont = BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED)
                break
            } catch (e: Exception) {
                // Ignore and try next
            }
        }
        
        val bf = baseFont ?: throw RuntimeException("Could not load any Chinese font")
        
        val cellH = paperHeight / labelsPerPage
        val contentWidth = 45 * MM_TO_PT
        val contentHeight = 28 * MM_TO_PT
        
        val positions = (0 until labelsPerPage).map { (labelsPerPage - 1 - it) * cellH }
        
        val expandedItems = items.flatMap { item -> List(item.copies) { item } }
        val chunks = expandedItems.chunked(labelsPerPage)
        val totalChunks = chunks.size
        
        for ((pageIndex, pageItems) in chunks.withIndex()) {
            onProgress?.invoke(pageIndex, totalChunks)
            // Draw cut lines (only draw if there is more than 1 label per page)
            if (labelsPerPage > 1) {
                cb.setLineDash(4f, 4f, 0f)
                for (k in 1 until labelsPerPage) {
                    cb.moveTo(5 * MM_TO_PT, k * cellH)
                    cb.lineTo(paperWidth - 5 * MM_TO_PT, k * cellH)
                }
                cb.stroke()
            }
            cb.setLineDash(0f)
            
            for (i in pageItems.indices) {
                val item = pageItems[i]
                val pos = positions[i]
                val cellTopY = pos + cellH
                
                // Calculate center of the cell
                val marginX = paperWidth * 0.08f // 8% horizontal margin
                val marginY = cellH * 0.08f // 8% vertical margin gives more breathing room
                
                // --- Brand Header Banner ---
                val headerTop = cellTopY - 2f // Pushed to the absolute top edge to maximize logo size
                
                // Brand Logo and Text (Centered horizontally)
                val brandText = "一颗小柿"
                val brandFontSize = 14f
                val tracking = 4f
                val brandWidth = bf.getWidthPoint(brandText, brandFontSize) + (brandText.length - 1) * tracking
                val iconSize = 40f // Extremely massive logo, pushed to physical limits
                val gap = 10f 
                val totalWidth = iconSize + gap + brandWidth
                
                // Shift the entire group left by 8pt to visually balance the heavy logo on the left
                val startX = (paperWidth - totalWidth) / 2 - 8f
                
                val logoX = startX
                val logoY = headerTop - iconSize
                
                // Vertically center text relative to logo (adjusting for font ascender)
                val brandBaselineY = logoY + (iconSize - brandFontSize) / 2f - 3f
                
                try {
                    val imgStream = this::class.java.getResourceAsStream("/shijimao_logo.png")
                    if (imgStream != null) {
                        val bytes = imgStream.readBytes()
                        val pdfImg = PdfImage.getInstance(bytes)
                        pdfImg.setAbsolutePosition(logoX, logoY)
                        pdfImg.scaleAbsolute(iconSize, iconSize)
                        cb.addImage(pdfImg)
                    }
                } catch (e: Exception) {
                    // Ignore if image not found
                }
                
                cb.beginText()
                cb.setGrayFill(0f)
                cb.setFontAndSize(bf, brandFontSize)
                cb.setCharacterSpacing(tracking) 
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, brandText, startX + iconSize + gap, brandBaselineY, 0f)
                cb.setCharacterSpacing(0f) // reset
                cb.endText()
                
                // Thick bottom line
                val lineY = logoY - 4f
                cb.setLineWidth(2f) // Very bold line
                cb.moveTo(marginX, lineY)
                cb.lineTo(paperWidth - marginX, lineY)
                cb.stroke()
                
                // --- Layout Variables ---
                val contentTop = lineY - 8f
                
                // Pin QR Code to bottom margin
                val qrSize = 16f * MM_TO_PT // Slightly reduced to fit massive header
                val qrX = paperWidth - marginX - qrSize
                val qrTextY = pos + marginY // Bottom align with margin
                val qrY = qrTextY + 9f // Place QR code just above its text
                
                val qrBytes = generateQRCode(item.barcode)
                val pdfImg = PdfImage.getInstance(qrBytes)
                pdfImg.setAbsolutePosition(qrX, qrY)
                pdfImg.scaleAbsolute(qrSize, qrSize)
                cb.addImage(pdfImg)
                
                cb.beginText()
                cb.setGrayFill(0.3f)
                cb.setFontAndSize(bf, 7.5f)
                cb.showTextAligned(PdfContentByte.ALIGN_CENTER, item.barcode, qrX + qrSize / 2, qrTextY, 0f)
                cb.endText()
                
                // --- Left Column (Text) ---
                val textX = marginX
                val maxTextWidth = qrX - textX - 4f // Leave 4pt padding before QR code
                
                val truncate = { text: String, fontSize: Float ->
                    if (bf.getWidthPoint(text, fontSize) <= maxTextWidth) text
                    else {
                        var temp = text
                        while (temp.isNotEmpty() && bf.getWidthPoint("$temp...", fontSize) > maxTextWidth) {
                            temp = temp.dropLast(1)
                        }
                        "$temp..."
                    }
                }

                cb.beginText()
                cb.setGrayFill(0f)
                cb.setFontAndSize(bf, 11f)
                val nameBaselineY = contentTop - 10f
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, truncate(item.name, 11f), textX, nameBaselineY, 0f)
                
                val priceBaselineY = qrTextY // Perfectly align price with QR text
                
                // Calculate dynamic vertical spacing for details
                val availableSpace = nameBaselineY - priceBaselineY
                // Use a weighted distribution: 1 unit between text lines, 1.5 units above the Price
                // Total units = 1 + 1 + 1 + 1.5 = 4.5
                val unit = availableSpace / 4.5f
                
                cb.setGrayFill(0.2f)
                cb.setFontAndSize(bf, 8.5f)
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, truncate("分类: ${item.category}", 8.5f), textX, nameBaselineY - unit, 0f)
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, truncate("材质: ${item.material}", 8.5f), textX, nameBaselineY - 2 * unit, 0f)
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, truncate("规格: ${item.spec}", 8.5f), textX, nameBaselineY - 3 * unit, 0f)
                
                cb.setGrayFill(0f)
                cb.setFontAndSize(bf, 9f) 
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, "RMB ", textX, priceBaselineY, 0f)
                val rmbWidth = bf.getWidthPoint("RMB ", 9f)
                
                // Auto-scale price font size to prevent overlapping QR code
                val maxPriceWidth = maxTextWidth - rmbWidth
                var priceFontSize = 16f
                while (priceFontSize > 8f && bf.getWidthPoint(item.price, priceFontSize) > maxPriceWidth) {
                    priceFontSize -= 0.5f
                }
                cb.setFontAndSize(bf, priceFontSize)
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, item.price, textX + rmbWidth, priceBaselineY, 0f)
                cb.endText()
            }
            
            if (pageIndex < totalChunks - 1) {
                document.newPage()
            }
        }
        
        onProgress?.invoke(totalChunks, totalChunks)
        
        document.close()
    }
    
    private fun generateQRCode(data: String): ByteArray {
        val writer = QRCodeWriter()
        // QR Code with a quiet zone (margin) of 1
        val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
        val bitMatrix = writer.encode(data, com.google.zxing.BarcodeFormat.QR_CODE, 300, 300, hints)
        val baos = ByteArrayOutputStream()
        com.google.zxing.client.j2se.MatrixToImageWriter.writeToStream(bitMatrix, "PNG", baos)
        return baos.toByteArray()
    }
}
