package com.example.scenic_navigation.events

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object SettingsBus {
    private val _events = MutableLiveData<Int>()
    val events: LiveData<Int> = _events

    fun notifySettingsChanged() {
        _events.value = (_events.value ?: 0) + 1
    }
}
