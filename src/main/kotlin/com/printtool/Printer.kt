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
        try {
            val printJob = PrinterJob.getPrinterJob()
            
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

            val printable = PDFPrintable(document, Scaling.ACTUAL_SIZE)
            printJob.setPrintable(printable)
            
            printJob.print() // Silent print to selected printer
        } finally {
            document.close()
        }
    }
}
