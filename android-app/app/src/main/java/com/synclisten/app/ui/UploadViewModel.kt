package com.synclisten.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synclisten.app.transfer.AudioFileInspector
import com.synclisten.app.transfer.AudioSelectionResult
import com.synclisten.app.transfer.BatchItemState
import com.synclisten.app.transfer.BatchUploadItem
import com.synclisten.app.transfer.BatchUploadState
import com.synclisten.app.transfer.UploadCoordinator
import com.synclisten.app.transfer.UploadRequest
import com.synclisten.app.transfer.UploadState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
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
    /** Batch upload state from singleton coordinator — survives navigation away */
    val batch: StateFlow<BatchUploadState> = uploadCoordinator.batchState

    private var inspectionJob: Job? = null
    private val isBusy: Boolean
        get() = inspectionJob?.isActive == true || upload.value.state == UploadState.Uploading ||
            batch.value.files.any { it.state == BatchItemState.Uploading }

    fun select(uri: Uri?) {
        if (isBusy) return
        if (uri == null) {
            mutableState.value = UploadSelectionState.Cancelled
            return
        }
        inspectionJob = viewModelScope.launch {
            mutableState.value = UploadSelectionState.Reading
            mutableState.value = when (val result = inspector.inspect(uri)) {
                is AudioSelectionResult.Ready -> UploadSelectionState.Ready(result)
                AudioSelectionResult.Unsupported ->
                    UploadSelectionState.Error("仅支持 MP3/FLAC/OGG/AAC/WAV/Opus/M4A/WMA")
                is AudioSelectionResult.Failed -> UploadSelectionState.Error(result.message)
            }
        }
    }

    fun selectMultiple(uris: List<Uri>) {
        if (isBusy) return
        if (uris.isEmpty()) {
            mutableState.value = UploadSelectionState.Cancelled
            return
        }
        val items = uris.map { uri ->
            BatchUploadItem(uri, uri.lastPathSegment ?: "unknown", 0, BatchItemState.Pending)
        }
        uploadCoordinator.enqueueBatch(items)

        inspectionJob = viewModelScope.launch {
            val current = uploadCoordinator.batchState.value
            val updated = current.files.toMutableList()
            for ((index, item) in updated.withIndex()) {
                updated[index] = item.copy(state = BatchItemState.Inspecting)
                uploadCoordinator.enqueueBatch(updated.toList())

                when (val result = inspector.inspect(item.uri)) {
                    is AudioSelectionResult.Ready -> {
                        updated[index] = item.copy(
                            fileName = result.file.fileName,
                            fileSize = result.file.fileSize,
                            state = BatchItemState.Ready,
                            localFile = result.file,
                        )
                    }
                    AudioSelectionResult.Unsupported -> {
                        updated[index] = item.copy(state = BatchItemState.Failed, error = "不支持的格式")
                    }
                    is AudioSelectionResult.Failed -> {
                        updated[index] = item.copy(state = BatchItemState.Failed, error = result.message)
                    }
                }
                uploadCoordinator.enqueueBatch(updated.toList())
            }
        }
    }

    fun upload() {
        if (isBusy) return
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

    fun uploadAll() {
        if (isBusy) return
        val session = homeController.state.value as? HomeState.InRoom ?: return
        uploadCoordinator.uploadBatch(
            roomId = session.room.roomId,
            uploaderId = session.member.userId,
            uploaderName = session.member.displayName,
        )
    }

    fun clearBatch() {
        if (!isBusy) uploadCoordinator.clearBatch()
    }

    override fun onCleared() {
        if (batch.value.files.any { it.state == BatchItemState.Pending || it.state == BatchItemState.Inspecting }) {
            uploadCoordinator.clearBatch()
        }
        super.onCleared()
    }
}
