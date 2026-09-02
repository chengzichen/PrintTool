package com.printtool

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.Memory
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.platform.win32.WinNT.HANDLEByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

data class PrintStatus(
    val totalPages: Int,
    val pagesPrinted: Int,
    val jobCount: Int,
    val errorMessage: String?
)

@Structure.FieldOrder("pDatatype", "pDevMode", "DesiredAccess")
open class PRINTER_DEFAULTS : Structure() {
    @JvmField var pDatatype: String? = null
    @JvmField var pDevMode: Pointer? = null
    @JvmField var DesiredAccess: Int = 0
}

@Structure.FieldOrder("JobId", "pPrinterName", "pMachineName", "pUserName", "pDocument", "pDatatype", "pStatus", "Status", "Priority", "Position", "TotalPages", "PagesPrinted", "Submitted")
open class JOB_INFO_1 : Structure {
    @JvmField var JobId: Int = 0
    @JvmField var pPrinterName: String? = null
    @JvmField var pMachineName: String? = null
    @JvmField var pUserName: String? = null
    @JvmField var pDocument: String? = null
    @JvmField var pDatatype: String? = null
    @JvmField var pStatus: String? = null
    @JvmField var Status: Int = 0
    @JvmField var Priority: Int = 0
    @JvmField var Position: Int = 0
    @JvmField var TotalPages: Int = 0
    @JvmField var PagesPrinted: Int = 0
    @JvmField var Submitted: ByteArray = ByteArray(16) // SYSTEMTIME is 16 bytes

    constructor() : super()
    constructor(p: Pointer) : super(p) { read() }
}

interface WinspoolEx : StdCallLibrary {
    companion object {
        val INSTANCE: WinspoolEx = Native.load("winspool.drv", WinspoolEx::class.java, W32APIOptions.UNICODE_OPTIONS)
        const val PRINTER_CONTROL_PURGE = 3
        const val PRINTER_ACCESS_ADMINISTER = 0x00000004
        const val PRINTER_ACCESS_USE = 0x00000008
        const val PRINTER_ALL_ACCESS = 0x000F000C
        
        const val JOB_STATUS_ERROR = 0x00000002
        const val JOB_STATUS_OFFLINE = 0x00000020
        const val JOB_STATUS_PAPEROUT = 0x00000040
        const val JOB_STATUS_USER_INTERVENTION = 0x00010000
    }

    fun OpenPrinter(pPrinterName: String, phPrinter: HANDLEByReference, pDefault: PRINTER_DEFAULTS?): Boolean
    fun SetPrinter(hPrinter: HANDLE, level: Int, pPrinter: Pointer?, command: Int): Boolean
    fun EnumJobs(hPrinter: HANDLE, firstJob: Int, noJobs: Int, level: Int, pJob: Pointer?, cbBuf: Int, pcbNeeded: IntByReference, pcReturned: IntByReference): Boolean
    fun ClosePrinter(hPrinter: HANDLE): Boolean
}

object JnaPrinterControl {
    /**
     * Purges all jobs in the specified printer's queue.
     * Returns true if successful, false otherwise.
     */
    fun purgePrintQueue(printerName: String): Boolean {
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("win")) return false
        
        return try {
            val hPrinter = HANDLEByReference()
            val defaults = PRINTER_DEFAULTS().apply {
                DesiredAccess = WinspoolEx.PRINTER_ACCESS_ADMINISTER
            }
            if (!WinspoolEx.INSTANCE.OpenPrinter(printerName, hPrinter, defaults)) return false
            val success = WinspoolEx.INSTANCE.SetPrinter(hPrinter.value, 0, null, WinspoolEx.PRINTER_CONTROL_PURGE)
            WinspoolEx.INSTANCE.ClosePrinter(hPrinter.value)
            success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Gets the current printing status: (Total Pages, Pages Printed, Job Count, Error Message)
     */
    fun getHardwarePrintStatus(printerName: String): PrintStatus? {
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("win")) return null
        
        return try {
            val hPrinter = HANDLEByReference()
            val defaults = PRINTER_DEFAULTS().apply {
                DesiredAccess = WinspoolEx.PRINTER_ACCESS_USE
            }
            if (!WinspoolEx.INSTANCE.OpenPrinter(printerName, hPrinter, defaults)) return null
            
            val pcbNeeded = IntByReference()
            val pcReturned = IntByReference()
            
            // Query buffer size needed
            WinspoolEx.INSTANCE.EnumJobs(hPrinter.value, 0, 10, 1, null, 0, pcbNeeded, pcReturned)
            
            var result = PrintStatus(0, 0, 0, null)
            
            if (pcbNeeded.value > 0) {
                val pJob = Memory(pcbNeeded.value.toLong())
                if (WinspoolEx.INSTANCE.EnumJobs(hPrinter.value, 0, 10, 1, pJob, pcbNeeded.value, pcbNeeded, pcReturned)) {
                    val jobsCount = pcReturned.value
                    if (jobsCount > 0) {
                        val jobRef = JOB_INFO_1(pJob)
                        val jobsArray = jobRef.toArray(jobsCount) as Array<*>
                        var totalPages = 0
                        var pagesPrinted = 0
                        var errorMessage: String? = null
                        
                        // Aggregate pages across all active jobs (usually just 1 for a PDF, but summing is safe)
                        for (jobObj in jobsArray) {
                            val job = jobObj as JOB_INFO_1
                            totalPages += job.TotalPages
                            pagesPrinted += job.PagesPrinted
                            
                            if ((job.Status and WinspoolEx.JOB_STATUS_PAPEROUT) != 0) {
                                errorMessage = "打印机缺纸 (Paper Out)"
                            } else if ((job.Status and WinspoolEx.JOB_STATUS_OFFLINE) != 0) {
                                errorMessage = "打印机离线 (Offline)"
                            } else if ((job.Status and WinspoolEx.JOB_STATUS_USER_INTERVENTION) != 0) {
                                errorMessage = "需要人工干预 (User Intervention)"
                            } else if ((job.Status and WinspoolEx.JOB_STATUS_ERROR) != 0) {
                                errorMessage = "打印出错 (Error)"
                            }
                        }
                        result = PrintStatus(totalPages, pagesPrinted, jobsCount, errorMessage)
                    }
                }
            }
            
            WinspoolEx.INSTANCE.ClosePrinter(hPrinter.value)
            result
        } catch (e: Exception) {
            null
        }
    }
}
