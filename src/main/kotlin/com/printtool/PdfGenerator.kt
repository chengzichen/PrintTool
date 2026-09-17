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
        val bcWidth = paperWidth * 0.9f
        val bcHeight = 10 * MM_TO_PT // Reduced from 14mm
        val textLeftOffset = paperWidth * 0.1f // 10% of width
        val barcodeLeftOffset = (paperWidth - bcWidth) / 2f
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
                
                val bcY = pos + cellH - bcHeight - 1 * MM_TO_PT // reduced top margin to 1mm
                
                // Generate Barcode image
                val barcodeBytes = generateBarcode(item.barcode)
                val pdfImg = PdfImage.getInstance(barcodeBytes)
                pdfImg.setAbsolutePosition(barcodeLeftOffset, bcY)
                pdfImg.scaleAbsolute(bcWidth, bcHeight)
                cb.addImage(pdfImg)
                
                // Draw texts
                cb.beginText()
                cb.setFontAndSize(bf, 7.5f) // Reduced barcode font
                // Center the text perfectly under the barcode
                cb.showTextAligned(PdfContentByte.ALIGN_CENTER, item.barcode, barcodeLeftOffset + bcWidth / 2, bcY - 3f * MM_TO_PT, 0f)
                cb.endText()
                
                cb.setRGBColorStroke(128, 128, 128)
                cb.moveTo(textLeftOffset, bcY - 4.5f * MM_TO_PT)
                cb.lineTo(paperWidth - textLeftOffset, bcY - 4.5f * MM_TO_PT)
                cb.stroke()
                cb.setRGBColorStroke(0, 0, 0)
                
                cb.beginText()
                cb.setFontAndSize(bf, 8.5f) // Reduced main text font
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, item.name, textLeftOffset, bcY - 8f * MM_TO_PT, 0f)
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, "分 类 : ${item.category}", textLeftOffset, bcY - 11.5f * MM_TO_PT, 0f)
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, "材 质 : ${item.material}", textLeftOffset, bcY - 15f * MM_TO_PT, 0f)
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, "规 格 : ${item.spec}", textLeftOffset, bcY - 18.5f * MM_TO_PT, 0f)
                cb.showTextAligned(PdfContentByte.ALIGN_LEFT, "价 格 : ￥${item.price}", textLeftOffset, bcY - 22.0f * MM_TO_PT, 0f)
                cb.endText()
            }
            document.newPage()
        }
        
        onProgress?.invoke(totalChunks, totalChunks)
        
        document.close()
    }
    
    private fun generateBarcode(data: String): ByteArray {
        val writer = Code128Writer()
        val bitMatrix = writer.encode(data, BarcodeFormat.CODE_128, 900, 200)
        val baos = ByteArrayOutputStream()
        MatrixToImageWriter.writeToStream(bitMatrix, "PNG", baos)
        return baos.toByteArray()
    }
}
