package com.printtool

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.File
import java.nio.charset.Charset

object CsvParser {
    fun parse(file: File): List<ProductItem> {
        val items = mutableListOf<ProductItem>()
        // Determine charset by trying to read the first line
        val firstLine = file.readLines(Charset.forName("GBK")).firstOrNull() ?: ""
        val charset = if (firstLine.contains("商品名称")) Charset.forName("GBK") else Charset.forName("UTF-8")
        
        val format = CSVFormat.Builder.create(CSVFormat.DEFAULT)
            .setHeader()
            .setSkipHeaderRecord(true)
            .build()
            
        val parser = CSVParser.parse(file, charset, format)
        for (record in parser) {
            val keys = record.toMap().keys
            fun getValue(columnName: String): String {
                val key = keys.firstOrNull { it?.contains(columnName) == true }
                return if (key != null) record.get(key) else ""
            }

            val name = getValue("商品名称")
            val category = getValue("商品分类")
            val spec = getValue("规格")
            val barcode = getValue("条码").trim()
            val status = getValue("当前状态")
            val price = getValue("建议售价").ifEmpty { getValue("采购单价") }
            val supplier = getValue("供应商")
            val material = getValue("材质")
            
            if (barcode.isNotEmpty()) {
                items.add(ProductItem(name, category, spec, barcode, status, price, supplier, material))
            }
        }
        return items
    }
}
