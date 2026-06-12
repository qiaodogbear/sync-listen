package com.synclisten.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synclisten.app.transfer.AudioFileInspector
import com.synclisten.app.transfer.AudioSelectionResult
import com.synclisten.app.transfer.UploadCoordinator
import com.synclisten.app.transfer.UploadRequest
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
    private val uploadCoordinator: UploadCoordinator,
    private val homeController: HomeController,
) : ViewModel() {
    private val mutableState = MutableStateFlow<UploadSelectionState>(UploadSelectionState.Empty)
    val state: StateFlow<UploadSelectionState> = mutableState
    val upload = uploadCoordinator.state

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

    fun upload() {
        val file = (mutableState.value as? UploadSelectionState.Ready)?.result?.file ?: return
        val session = homeController.state.value as? HomeState.InRoom ?: return
        viewModelScope.launch {
            uploadCoordinator.upload(
                UploadRequest(
                    roomId = session.room.roomId,
                    uploaderId = session.member.userId,
                    uploaderName = session.member.displayName,
                    file = file,
                ),
            )
        }
    }
}
