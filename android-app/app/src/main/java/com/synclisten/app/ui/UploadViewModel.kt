package com.synclisten.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synclisten.app.transfer.AudioFileInspector
import com.synclisten.app.transfer.AudioSelectionResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface UploadSelectionState {
    data object Empty : UploadSelectionState
    data object Reading : UploadSelectionState
    data object Cancelled : UploadSelectionState
    data class Ready(val result: AudioSelectionResult.Ready) : UploadSelectionState
    data class Error(val message: String) : UploadSelectionState
}

@HiltViewModel
class UploadViewModel @Inject constructor(
    private val inspector: AudioFileInspector,
) : ViewModel() {
    private val mutableState = MutableStateFlow<UploadSelectionState>(UploadSelectionState.Empty)
    val state: StateFlow<UploadSelectionState> = mutableState

    fun select(uri: Uri?) {
        if (uri == null) {
            mutableState.value = UploadSelectionState.Cancelled
            return
        }
        viewModelScope.launch {
            mutableState.value = UploadSelectionState.Reading
            mutableState.value = when (val result = inspector.inspect(uri)) {
                is AudioSelectionResult.Ready -> UploadSelectionState.Ready(result)
                AudioSelectionResult.Unsupported -> UploadSelectionState.Error("仅支持 MP3 和 FLAC")
                is AudioSelectionResult.Failed -> UploadSelectionState.Error(result.message)
            }
        }
    }
}
