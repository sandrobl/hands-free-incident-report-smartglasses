// File: app/src/main/java/com/handsfree_incident_report_mobile/ui/HomeViewModelFactory.kt
package com.unisg.hands_free_incident_report_smartglasses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.unisg.hands_free_incident_report_smartglasses.VideoRepository

class HomeViewModelFactory(private val repository: VideoRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}