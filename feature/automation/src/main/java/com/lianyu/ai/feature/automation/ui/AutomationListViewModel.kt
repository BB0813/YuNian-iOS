package com.lianyu.ai.feature.automation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lianyu.ai.feature.automation.AutomationScheduler
import com.lianyu.ai.feature.automation.data.Automation
import com.lianyu.ai.feature.automation.data.AutomationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AutomationListViewModel(application: Application) : AndroidViewModel(application) {

    private val store = AutomationStore(application)

    val automations: StateFlow<List<Automation>> = store.flow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            store.setEnabled(id, enabled)
            val target = store.list().firstOrNull { it.id == id } ?: return@launch
            if (enabled) {
                AutomationScheduler.reschedule(getApplication(), target)
            } else {
                AutomationScheduler.cancel(getApplication(), id)
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            store.delete(id)
            AutomationScheduler.cancel(getApplication(), id)
        }
    }
}
