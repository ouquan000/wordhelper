package com.kaoyan.wordhelper.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val SUBMIT_BUSY = "正在处理上一步，稍等一下"

internal class LearningSubmissionGate(
    private val viewModelScope: CoroutineScope
) {
    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()
    val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun launchSubmit(block: suspend () -> Unit) {
        if (_isSubmitting.value) {
            // 1.5.2：重入不再静默丢弃，给一句轻提示。
            _message.value = SUBMIT_BUSY
            return
        }
        _isSubmitting.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _isSubmitting.value = false
            }
        }
    }
}
