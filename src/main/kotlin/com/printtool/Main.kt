package com.printtool

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import java.awt.image.BufferedImage
import java.awt.Color as AwtColor
import com.google.zxing.BarcodeFormat
import com.google.zxing.oned.Code128Writer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import androidx.compose.ui.DragData
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.onExternalDrag
import java.util.prefs.Preferences

@Composable
fun App() {
    var items by remember { mutableStateOf<List<ProductItem>>(emptyList()) }
    var isProcessing by remember { mutableStateOf(false) }
    var printProgress by remember { mutableStateOf(0f) }
    var statusMessage by remember { mutableStateOf("就绪 (Ready)") }
    var previewItem by remember { mutableStateOf<ProductItem?>(null) }
    
    val prefs = remember { Preferences.userRoot().node("com.printtool.config") }
    
    var printers by remember { mutableStateOf(emptyList<String>()) }
    var selectedPrinter by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    
    var paperWidth by remember { mutableStateOf(prefs.get("paperWidth", "80")) }
    var paperHeight by remember { mutableStateOf(prefs.get("paperHeight", "130")) }
    var labelsPerPage by remember { mutableStateOf(prefs.get("labelsPerPage", "3")) }
    
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val available = Printer.getPrinters()
            printers = available
            if (available.isNotEmpty()) {
                val savedPrinter = prefs.get("selectedPrinter", null)
                if (savedPrinter != null && available.contains(savedPrinter)) {
                    selectedPrinter = savedPrinter
                } else {
                    val defPrinter = Printer.getDefaultPrinter()
                    selectedPrinter = if (defPrinter != null && available.contains(defPrinter)) defPrinter else available.first()
                }
            }
        }
    }
    
    LaunchedEffect(paperWidth) { prefs.put("paperWidth", paperWidth) }
    LaunchedEffect(paperHeight) { prefs.put("paperHeight", paperHeight) }
    LaunchedEffect(labelsPerPage) { prefs.put("labelsPerPage", labelsPerPage) }
    LaunchedEffect(selectedPrinter) { selectedPrinter?.let { prefs.put("selectedPrinter", it) } }

    @OptIn(ExperimentalComposeUiApi::class)
    MaterialTheme(colorScheme = lightColorScheme()) {
        Column(Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onExternalDrag(onDrop = { externalDragValue ->
                val dragData = externalDragValue.dragData
                if (dragData is DragData.FilesList) {
                    val fileUri = dragData.readFiles().firstOrNull()
                    if (fileUri != null) {
                        coroutineScope.launch {
                            isProcessing = true
                            statusMessage = "正在读取拖入的文件..."
                            try {
                                var path = fileUri
                                if (path.startsWith("file://")) {
                                    path = path.substring(7)
                                } else if (path.startsWith("file:/")) {
                                    path = path.substring(6)
                                }
                                if (path.startsWith("/") && (path.contains(":\\") || path.contains(":/"))) {
                                    path = path.substring(1)
                                }
                                path = java.net.URLDecoder.decode(path, "UTF-8")
                                
                                val file = File(path)
                                val parsedItems = withContext(Dispatchers.IO) { CsvParser.parse(file) }
                                items = parsedItems
                                statusMessage = "成功读取 ${items.size} 条记录"
                            } catch (e: Exception) {
                                statusMessage = "读取失败: ${e.message}"
                                e.printStackTrace()
                            } finally {
                                isProcessing = false
                            }
                        }
                    }
                }
            })
            .padding(16.dp)) {
            Text("服装标签打印系统", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        val file = selectFile()
                        if (file != null) {
                            coroutineScope.launch {
                                isProcessing = true
                                statusMessage = "正在读取文件..."
                                try {
                                    val parsedItems = withContext(Dispatchers.IO) { CsvParser.parse(file) }
                                    items = parsedItems
                                    statusMessage = "成功读取 ${items.size} 条记录"
                                } catch (e: Exception) {
                                    statusMessage = "读取失败: ${e.message}"
                                    e.printStackTrace()
                                } finally {
                                    isProcessing = false
                                }
                            }
                        }
                    },
                    enabled = !isProcessing
                ) {
                    Text("导入模板 (支持拖拽文件)")
                }
                
                OutlinedButton(onClick = { items = emptyList() }) {
                    Text("清空列表")
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Row 1: Settings
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("目标打印机：", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
                        Box {
                            OutlinedButton(onClick = { 
                                expanded = true 
                                coroutineScope.launch(Dispatchers.IO) {
                                    val available = Printer.getPrinters()
                                    printers = available
                                    // Auto select default if selectedPrinter is no longer available
                                    if (selectedPrinter != null && !available.contains(selectedPrinter)) {
                                        selectedPrinter = Printer.getDefaultPrinter() ?: available.firstOrNull()
                                    }
                                }
                            }) {
                                Text(selectedPrinter ?: "选择打印机")
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                printers.forEach { printerName ->
                                    DropdownMenuItem(
                                        text = { Text(printerName) },
                                        onClick = {
                                            selectedPrinter = printerName
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("纸宽(mm):", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(value = paperWidth, onValueChange = { paperWidth = it }, modifier = Modifier.width(70.dp), singleLine = true)
                        
                        Text("纸高(mm):", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(value = paperHeight, onValueChange = { paperHeight = it }, modifier = Modifier.width(70.dp), singleLine = true)
                        
                        Text("每页张数:", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(value = labelsPerPage, onValueChange = { labelsPerPage = it }, modifier = Modifier.width(70.dp), singleLine = true)
                    }
                }
                
                // Row 2: Actions
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                
                    Button(
                    onClick = {
                        val selectedItems = items.filter { it.selected }
                        if (selectedItems.isEmpty()) return@Button
                        coroutineScope.launch {
                            isProcessing = true
                            statusMessage = "正在生成 PDF 并打印..."
                            try {
                                val tempFile = withContext(Dispatchers.IO) {
                                    val f = File.createTempFile("print_task_", ".pdf")
                                    printProgress = 0f
                                    PdfGenerator.createPdf(
                                        selectedItems, 
                                        f,
                                        paperWidthMm = paperWidth.toFloatOrNull() ?: 80f,
                                        paperHeightMm = paperHeight.toFloatOrNull() ?: 130f,
                                        labelsPerPage = labelsPerPage.toIntOrNull() ?: 3
                                    ) { current, total ->
                                        printProgress = current.toFloat() / total.toFloat()
                                    }
                                    f
                                }
                                statusMessage = "PDF生成成功，发送至打印机..."
                                withContext(Dispatchers.IO) {
                                    Printer.printPdf(tempFile, selectedPrinter)
                                }
                                
                                statusMessage = "进入硬件打印阶段..."
                                selectedPrinter?.let { printer ->
                                    var done = false
                                    while (!done && isProcessing) {
                                        val status = withContext(Dispatchers.IO) { JnaPrinterControl.getHardwarePrintStatus(printer) }
                                        if (status != null) {
                                            val (totalPages, pagesPrinted, jobCount, errorMessage) = status
                                            if (jobCount == 0) {
                                                done = true
                                            } else {
                                                if (errorMessage != null) {
                                                    statusMessage = "异常: $errorMessage | 吐纸进度: $pagesPrinted / $totalPages"
                                                } else {
                                                    statusMessage = "硬件吐纸进度: $pagesPrinted / $totalPages (队列排队任务数: $jobCount)"
                                                }
                                                printProgress = if (totalPages > 0) pagesPrinted.toFloat() / totalPages.toFloat() else 0f
                                            }
                                        } else {
                                            done = true
                                        }
                                        if (!done) {
                                            kotlinx.coroutines.delay(1000)
                                        }
                                    }
                                }
                                if (isProcessing) {
                                    statusMessage = "打印彻底完成！(硬件端已空闲)"
                                }
                            } catch (e: Exception) {
                                statusMessage = "打印失败: ${e.message}"
                                e.printStackTrace()
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                    enabled = items.any { it.selected } && !isProcessing
                ) {
                    Text("打印选中标签")
                }
                
                OutlinedButton(
                    onClick = {
                        selectedPrinter?.let { printer ->
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val success = JnaPrinterControl.purgePrintQueue(printer)
                                    if (success) {
                                        isProcessing = false
                                        statusMessage = "JNA 底层调用成功：已清空 [$printer] 打印队列！"
                                    } else {
                                        statusMessage = "JNA 调用失败，可能是权限不足或找不到打印机。"
                                    }
                                } catch (e: Exception) {
                                    statusMessage = "清空队列异常: ${e.message}"
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                ) {
                    Text("终止/清空打印机队列")
                }
                } // End of actions Row
            } // End of Column
            
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("状态: $statusMessage", color = if (statusMessage.contains("失败")) Color.Red else Color.Unspecified)
                if (isProcessing && printProgress > 0f) {
                    Spacer(Modifier.width(16.dp))
                    LinearProgressIndicator(progress = { printProgress }, modifier = Modifier.width(200.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${(printProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(16.dp))
            
            // Table Header
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val allSelected = items.isNotEmpty() && items.all { it.selected }
                Checkbox(checked = allSelected, onCheckedChange = { isChecked ->
                    items = items.map { it.copy(selected = isChecked) }
                }, modifier = Modifier.weight(0.5f))
                Text("条码 (Barcode)", Modifier.weight(2f), fontWeight = FontWeight.Bold)
                Text("商品名称 (Name)", Modifier.weight(2f), fontWeight = FontWeight.Bold)
                Text("规格 (Spec)", Modifier.weight(1.5f), fontWeight = FontWeight.Bold)
                Text("价格 (Price)", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("份数", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("操作", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            }
            
            // Table Content
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(items) { index, item ->
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.selected, onCheckedChange = { isChecked -> 
                            items = items.toMutableList().apply { set(index, item.copy(selected = isChecked)) } 
                        }, modifier = Modifier.weight(0.5f))
                        TableCellTextField(item.barcode, { items = items.toMutableList().apply { set(index, item.copy(barcode = it)) } }, Modifier.weight(2f))
                        TableCellTextField(item.name, { items = items.toMutableList().apply { set(index, item.copy(name = it)) } }, Modifier.weight(2f))
                        TableCellTextField(item.spec, { items = items.toMutableList().apply { set(index, item.copy(spec = it)) } }, Modifier.weight(1.5f))
                        TableCellTextField(item.price, { items = items.toMutableList().apply { set(index, item.copy(price = it)) } }, Modifier.weight(1f))
                        
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if (item.copies > 0) items = items.toMutableList().apply { set(index, item.copy(copies = item.copies - 1)) } }, modifier = Modifier.size(24.dp)) { Text("-") }
                            Text("${item.copies}", modifier = Modifier.padding(horizontal = 4.dp))
                            IconButton(onClick = { items = items.toMutableList().apply { set(index, item.copy(copies = item.copies + 1)) } }, modifier = Modifier.size(24.dp)) { Text("+") }
                        }
                        
                        Button(onClick = { previewItem = item }, Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                            Text("🔍 预览")
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                }
            }
        }
        
        if (previewItem != null) {
            LabelPreviewDialog(previewItem!!) { previewItem = null }
        }
    }
}

@Composable
fun TableCellTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.padding(end = 4.dp).background(Color.White).border(1.dp, Color.LightGray).padding(4.dp),
        singleLine = true
    )
}

fun generateBarcodeBitmap(data: String): ImageBitmap {
    val writer = Code128Writer()
    val matrix = writer.encode(data, BarcodeFormat.CODE_128, 300, 100)
    val width = matrix.width
    val height = matrix.height
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    for (x in 0 until width) {
        for (y in 0 until height) {
            image.setRGB(x, y, if (matrix.get(x, y)) AwtColor.BLACK.rgb else AwtColor.WHITE.rgb)
        }
    }
    return image.toComposeImageBitmap()
}

@Composable
fun LabelPreviewDialog(item: ProductItem, onDismiss: () -> Unit) {
    val state = rememberDialogState(width = 400.dp, height = 300.dp)
    DialogWindow(onCloseRequest = onDismiss, title = "标签预览", state = state) {
        Box(Modifier.fillMaxSize().background(Color.LightGray), contentAlignment = Alignment.Center) {
            val widthDp = 320.dp
            
            Column(Modifier.width(widthDp).wrapContentHeight().background(Color.White).padding(16.dp)) {
                val barcodeBitmap = remember(item.barcode) { 
                    try { generateBarcodeBitmap(item.barcode) } catch (e: Exception) { null } 
                }
                if (barcodeBitmap != null) {
                    Image(barcodeBitmap, contentDescription = "Barcode", modifier = Modifier.height(60.dp).fillMaxWidth(), contentScale = ContentScale.FillBounds)
                }
                Text(item.barcode, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
                
                Spacer(Modifier.height(8.dp))
                Divider(color = Color.Gray, thickness = 1.dp)
                Spacer(Modifier.height(8.dp))
                
                Text("尺 码 : ${item.spec}", style = MaterialTheme.typography.bodyMedium)
                Text("价 格 : ${item.price}", style = MaterialTheme.typography.bodyMedium)
                Text("材 质 : ${item.name}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

fun selectFile(): File? {
    // We use JFileChooser for better filtering, or FileDialog for native look
    val chooser = JFileChooser()
    chooser.dialogTitle = "选择数据模板文件 (Select Template)"
    val filter = FileNameExtensionFilter("Excel/CSV Files", "xls", "csv", "xlsx")
    chooser.fileFilter = filter
    val result = chooser.showOpenDialog(null)
    if (result == JFileChooser.APPROVE_OPTION) {
        return chooser.selectedFile
    }
    return null
}

fun main() = application {
    val state = rememberWindowState(size = DpSize(1000.dp, 800.dp))
    Window(
        onCloseRequest = ::exitApplication, 
        title = "服装标签打印系统 (PrintTool)",
        icon = painterResource("icon.png"),
        state = state
    ) {
        App()
    }
}
