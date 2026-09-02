package com.printtool

import com.lowagie.text.Document
import com.lowagie.text.Rectangle
import com.lowagie.text.pdf.BaseFont
import com.lowagie.text.pdf.PdfContentByte
import com.lowagie.text.pdf.PdfWriter
import com.lowagie.text.Image as PdfImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.oned.Code128Writer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object PdfGenerator {
    private const val MM_TO_PT = 2.83465f
    private const val PAPER_WIDTH = 80 * MM_TO_PT
    private const val PAPER_HEIGHT = 130 * MM_TO_PT
    private const val LABELS_PER_PAGE = 3
    private const val LEFT_OFFSET = 12 * MM_TO_PT
    
    fun createPdf(items: List<ProductItem>, outputFile: File) {
        val document = Document(Rectangle(PAPER_WIDTH, PAPER_HEIGHT))
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
        
        val cellH = PAPER_HEIGHT / LABELS_PER_PAGE
        val bcWidth = 46 * MM_TO_PT
        val bcHeight = 11 * MM_TO_PT
        val positions = (0 until LABELS_PER_PAGE).map { (LABELS_PER_PAGE - 1 - it) * cellH }
        
        val expandedItems = items.flatMap { item -> List(item.copies) { item } }
        val chunks = expandedItems.chunked(LABELS_PER_PAGE)
        
        for (pageItems in chunks) {
            // Draw cut lines
            cb.setLineDash(4f, 4f, 0f)
            cb.moveTo(5 * MM_TO_PT, cellH)
            cb.lineTo(PAPER_WIDTH - 5 * MM_TO_PT, cellH)
            cb.moveTo(5 * MM_TO_PT, 2 * cellH)
            cb.lineTo(PAPER_WIDTH - 5 * MM_TO_PT, 2 * cellH)
            cb.stroke()
            cb.setLineDash(0f)
            
            for (i in pageItems.indices) {
                val item = pageItems[i]
                val pos = positions[i]
                
                val bcY = pos + cellH - bcHeight - 4 * MM_TO_PT
                
                // Generate Barcode image
                val barcodeBytes = generateBarcode(item.barcode)
                val pdfImg = PdfImage.getInstance(barcodeBytes)
                pdfImg.setAbsolutePosition(LEFT_OFFSET, bcY)
                pdfImg.scaleAbsolute(bcWidth, bcHeight)
                cb.addImage(pdfImg)
                
                // Draw texts
                cb.beginText()
                cb.setFontAndSize(bf, 8.5f)
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, item.barcode, LEFT_OFFSET + 1 * MM_TO_PT, bcY - 4 * MM_TO_PT, 0f)
                cb.endText()
                
                cb.setRGBColorStroke(128, 128, 128)
                cb.moveTo(LEFT_OFFSET, bcY - 6 * MM_TO_PT)
                cb.lineTo(LEFT_OFFSET + 52 * MM_TO_PT, bcY - 6 * MM_TO_PT)
                cb.stroke()
                cb.setRGBColorStroke(0, 0, 0)
                
                cb.beginText()
                cb.setFontAndSize(bf, 9f)
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, "尺 码 : ${item.spec}", LEFT_OFFSET + 1 * MM_TO_PT, bcY - 11 * MM_TO_PT, 0f)
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, "价 格 : ${item.price}", LEFT_OFFSET + 1 * MM_TO_PT, bcY - 17.5f * MM_TO_PT, 0f)
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, "材 质 : ${item.name}", LEFT_OFFSET + 1 * MM_TO_PT, bcY - 24 * MM_TO_PT, 0f)
                cb.endText()
            }
            document.newPage()
        }
        document.close()
    }
    
    private fun generateBarcode(data: String): ByteArray {
        val writer = Code128Writer()
        val bitMatrix = writer.encode(data, BarcodeFormat.CODE_128, 300, 100)
        val baos = ByteArrayOutputStream()
        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", baos)
        return baos.toByteArray()
    }
}
