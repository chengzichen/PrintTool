package com.printtool

data class ProductItem(
    val name: String,
    val category: String,
    val spec: String,
    val barcode: String,
    val status: String,
    val price: String,
    val supplier: String,
    val material: String = "",
    val copies: Int = 1,
    val selected: Boolean = true
)
