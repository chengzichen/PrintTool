package com.printtool

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.printing.PDFPrintable
import org.apache.pdfbox.printing.Scaling
import java.awt.print.PrinterJob
import java.io.File
import javax.print.PrintService
import javax.print.PrintServiceLookup

object Printer {

    fun getPrinters(): List<String> {
        return PrintServiceLookup.lookupPrintServices(null, null).map { it.name }
    }

    fun getDefaultPrinter(): String? {
        return PrintServiceLookup.lookupDefaultPrintService()?.name
    }

    fun printPdf(pdfFile: File, printerName: String? = null) {
        val document = PDDocument.load(pdfFile)
        var printJob: PrinterJob? = null
        var previousPageFormat: java.awt.print.PageFormat? = null
        try {
            printJob = PrinterJob.getPrinterJob()
            // Keep a snapshot of the job's original page format. The custom
            // paper size below is only for this print job and must not leak
            // into the next job or the system printer defaults.
            previousPageFormat = printJob.defaultPage()
            
            val printService: PrintService? = if (printerName != null) {
                PrintServiceLookup.lookupPrintServices(null, null).find { it.name == printerName }
            } else {
                PrintServiceLookup.lookupDefaultPrintService()
            }
            
            if (printService != null) {
                printJob.printService = printService
            } else {
                throw RuntimeException("未在系统中找到指定打印机！(Printer not found)")
            }

            val attributes = javax.print.attribute.HashPrintRequestAttributeSet()
            
            val mediaBox = document.getPage(0).mediaBox
            val paper = java.awt.print.Paper()
            paper.setSize(mediaBox.width.toDouble(), mediaBox.height.toDouble())
            paper.setImageableArea(0.0, 0.0, mediaBox.width.toDouble(), mediaBox.height.toDouble())
            
            val pageFormat = java.awt.print.PageFormat()
            pageFormat.paper = paper
            pageFormat.orientation = java.awt.print.PageFormat.PORTRAIT
            
            val book = java.awt.print.Book()
            // Using ACTUAL_SIZE mathematically guarantees 1:1 printing without driver zooming
            book.append(PDFPrintable(document, Scaling.ACTUAL_SIZE), pageFormat, document.numberOfPages)
            
            printJob.setPageable(book)
            
            printJob.print(attributes) // Silent print to selected printer
        } finally {
            // Restore the per-job page format even when printing fails. Java
            // PrintService does not persist this PageFormat, but restoring it
            // explicitly prevents future reuse from inheriting this job's
            // temporary 60x40/80x130 dimensions.
            if (printJob != null && previousPageFormat != null) {
                runCatching { printJob.defaultPage(previousPageFormat) }
            }
            document.close()
        }
    }
}
