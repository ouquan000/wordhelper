package com.kaoyan.wordhelper.util

internal enum class RailFeedback {
    Up, Down;

    fun submit(
        reason: String? = null,
        comment: String = "",
        onUp: (Int, List<String>, String) -> Unit,
        onDown: (Int, List<String>, String) -> Unit,
        onRewrite: (String) -> Unit
    ) {
        val reasons = listOfNotNull(reason)
        when (this) {
            Up -> onUp(1, reasons, comment)
            Down -> {
                onDown(1, reasons, comment)
                val instruction = (reasons + comment).filter(String::isNotBlank).joinToString("；")
                if (instruction.isNotEmpty()) onRewrite(instruction)
            }
        }
    }
}
