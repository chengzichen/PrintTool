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
import androidx.compose.ui.window.application
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

@Composable
fun App() {
    var items by remember { mutableStateOf<List<ProductItem>>(emptyList()) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("就绪 (Ready)") }
    var previewItem by remember { mutableStateOf<ProductItem?>(null) }
    
    var printers by remember { mutableStateOf(emptyList<String>()) }
    var selectedPrinter by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val available = Printer.getPrinters()
            printers = available
            if (available.isNotEmpty()) {
                val defPrinter = Printer.getDefaultPrinter()
                selectedPrinter = if (defPrinter != null && available.contains(defPrinter)) defPrinter else available.first()
            }
        }
    }

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
                }
                
                OutlinedButton(onClick = { items = emptyList() }) {
                    Text("清空列表")
                }
                
                Box {
                    OutlinedButton(onClick = { expanded = true }) {
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
                
                Button(
                    onClick = {
                        if (items.isEmpty()) return@Button
                        coroutineScope.launch {
                            isProcessing = true
                            statusMessage = "正在生成 PDF 并打印..."
                            try {
                                val tempFile = withContext(Dispatchers.IO) {
                                    val f = File.createTempFile("print_task_", ".pdf")
                                    PdfGenerator.createPdf(items, f)
                                    f
                                }
                                statusMessage = "PDF生成成功，发送至打印机..."
                                withContext(Dispatchers.IO) {
                                    Printer.printPdf(tempFile, selectedPrinter)
                                    // Optional: delete temp file after printing
                                    // tempFile.delete()
                                }
                                statusMessage = "打印完成！(Printed successfully)"
                            } catch (e: Exception) {
                                statusMessage = "打印失败: ${e.message}"
                                e.printStackTrace()
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                    enabled = items.isNotEmpty() && !isProcessing
                ) {
                    Text("打印所有标签 (Print All)")
                }
                
                OutlinedButton(
                    onClick = {
                        selectedPrinter?.let { printer ->
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val process = ProcessBuilder(
                                        "powershell.exe", 
                                        "-NoProfile", 
                                        "-Command", 
                                        "Get-PrintJob -PrinterName '$printer' | Remove-PrintJob"
                                    ).start()
                                    process.waitFor()
                                    statusMessage = "已向 Windows 发送指令：清空 [$printer] 打印队列！"
                                } catch (e: Exception) {
                                    statusMessage = "清空队列失败: ${e.message}"
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                ) {
                    Text("终止/清空打印机队列")
                }
            }
            
            Spacer(Modifier.height(16.dp))
            Text("状态: $statusMessage", color = if (statusMessage.contains("失败")) Color.Red else Color.Unspecified)
            Spacer(Modifier.height(16.dp))
            
            // Table Header
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
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
    DialogWindow(onCloseRequest = onDismiss, title = "标签预览") {
        Box(Modifier.fillMaxSize().background(Color.LightGray), contentAlignment = Alignment.Center) {
            val widthDp = 320.dp
            val heightDp = 172.dp
            
            Column(Modifier.size(widthDp, heightDp).background(Color.White).padding(16.dp)) {
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
    Window(
        onCloseRequest = ::exitApplication, 
        title = "服装标签打印系统 (PrintTool)",
        icon = painterResource("icon.png")
    ) {
        App()
    }
}
