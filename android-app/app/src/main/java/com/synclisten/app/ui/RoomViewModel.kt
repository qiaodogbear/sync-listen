package com.synclisten.app.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class RoomViewModel @Inject constructor(val session: RoomSessionController) : ViewModel()
