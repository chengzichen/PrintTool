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
                val marginY = cellH * 0.12f // 12% vertical margin
                
                // --- Brand Header Banner ---
                val headerTop = cellTopY - marginY
                
                // Thin top line
                cb.setLineWidth(0.5f)
                cb.setGrayStroke(0f)
                cb.moveTo(marginX, headerTop)
                cb.lineTo(paperWidth - marginX, headerTop)
                cb.stroke()
                
                // Brand Text
                val brandBaselineY = headerTop - 11f
                cb.beginText()
                cb.setGrayFill(0f)
                cb.setFontAndSize(bf, 11f)
                cb.setCharacterSpacing(7f) // Extreme tracking for high-end look
                cb.showTextAligned(PdfContentByte.ALIGN_CENTER, "一颗小柿", paperWidth / 2, brandBaselineY, 0f)
                cb.setCharacterSpacing(0f) // reset
                cb.endText()
                
                // Thick bottom line
                val lineY = brandBaselineY - 5f
                cb.setLineWidth(2f) // Very bold line
                cb.moveTo(marginX, lineY)
                cb.lineTo(paperWidth - marginX, lineY)
                cb.stroke()
                
                // --- Layout Variables ---
                val contentTop = lineY - 8f
                
                // --- Right Column (QR Code) ---
                val qrSize = 18f * MM_TO_PT // Slightly smaller to fit banner
                val qrX = paperWidth - marginX - qrSize
                val qrY = contentTop - qrSize // Top of QR aligns with contentTop
                
                val qrBytes = generateQRCode(item.barcode)
                val pdfImg = PdfImage.getInstance(qrBytes)
                pdfImg.setAbsolutePosition(qrX, qrY)
                pdfImg.scaleAbsolute(qrSize, qrSize)
                cb.addImage(pdfImg)
                
                cb.beginText()
                cb.setGrayFill(0.3f)
                cb.setFontAndSize(bf, 7.5f)
                val qrTextY = qrY - 3f * MM_TO_PT
                cb.showTextAligned(PdfContentByte.ALIGN_CENTER, item.barcode, qrX + qrSize / 2, qrTextY, 0f)
                cb.endText()
                
                // --- Left Column (Text) ---
                val textX = marginX
                cb.beginText()
                cb.setGrayFill(0f)
                cb.setFontAndSize(bf, 11f)
                val nameBaselineY = contentTop - 10f
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, item.name, textX, nameBaselineY, 0f)
                
                val priceBaselineY = qrTextY // Perfectly align price with QR text
                
                // Calculate dynamic vertical spacing for details
                val availableSpace = nameBaselineY - priceBaselineY
                val step = availableSpace / 4f
                
                cb.setGrayFill(0.2f)
                cb.setFontAndSize(bf, 8.5f)
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, "分类: ${item.category}", textX, nameBaselineY - step, 0f)
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, "材质: ${item.material}", textX, nameBaselineY - 2 * step, 0f)
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, "规格: ${item.spec}", textX, nameBaselineY - 3 * step, 0f)
                
                cb.setGrayFill(0f)
                cb.setFontAndSize(bf, 9f) 
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, "RMB ", textX, priceBaselineY, 0f)
                val rmbWidth = bf.getWidthPoint("RMB ", 9f)
                cb.setFontAndSize(bf, 16f) // Massive bold price
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
