package com.lianyu.ai.feature.companion.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.DefaultCompanionSeeder
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.database.repository.CompanionRepository
import com.lianyu.ai.common.ImageUtils
import com.lianyu.ai.network.AiService
import androidx.core.content.edit
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.text.RegexOption

class CreateCompanionViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CompanionRepository(AppDatabase.getDatabase(application).companionDao())

    private val _existingCompanion = MutableStateFlow<CompanionEntity?>(null)
    val existingCompanion: StateFlow<CompanionEntity?> = _existingCompanion

    private val _saveCompleted = MutableStateFlow(false)
    val saveCompleted: StateFlow<Boolean> = _saveCompleted

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val aiService = AiService(getApplication())

    fun loadCompanion(id: Long) {
        viewModelScope.launch {
            _existingCompanion.value = repository.getCompanionById(id)
        }
    }

    /**
     * 直接保存人设
     */
    fun saveCompanion(companion: CompanionEntity, isEditMode: Boolean) {
        viewModelScope.launch {
            val avatarUrl = companion.avatarUrl
            val savedAvatar = if (avatarUrl != null && avatarUrl.startsWith("content://")) {
                ImageUtils.saveUriToInternalStorage(getApplication(), avatarUrl)
            } else avatarUrl

            val companionToSave = companion.copy(avatarUrl = savedAvatar)
            if (isEditMode) {
                repository.updateCompanion(companionToSave)
            } else {
                repository.insertCompanion(companionToSave)
            }
            _saveCompleted.value = true
        }
    }

    fun deleteCompanion(companion: CompanionEntity) {
        viewModelScope.launch {
            if (companion.tags.orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .any { it == DefaultCompanionSeeder.LEGACY_TAG }
            ) {
                getApplication<Application>()
                    .getSharedPreferences("default_companion", android.content.Context.MODE_PRIVATE)
                    .edit { putBoolean("deleted_by_user", true) }
            }
            repository.deleteCompanion(companion)
        }
    }

    fun generatePersonaByAi(
        name: String,
        referenceCharacter: String? = null,
        extraHint: String? = null,
        onResult: (String) -> Unit
    ) {
        if (name.isBlank()) return
        _isGenerating.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val refPart = if (!referenceCharacter.isNullOrBlank()) {
                    "\n参考角色风格：$referenceCharacter"
                } else ""
                val hintPart = if (!extraHint.isNullOrBlank()) {
                    "\n额外要求：$extraHint"
                } else ""
                val prompt = """你是一个专业的人设/角色设定生成器。请为名为「$name」的角色生成详细、完整、不截断的人设。

要求：
1. 输出纯文本，不要JSON、不要markdown格式符
2. 必须包含以下完整板块，每个板块都要有具体内容：
   - 性格特点（5-8条，立体有层次，有优点也有小缺点）
   - 说话风格（语气、用词习惯、口头禅、特殊表达方式）
   - 背景故事（成长经历、家庭环境、重要转折点，要有记忆点）
   - 行为习惯（日常爱好、小动作、饮食偏好、作息等细节）
   - 情感模式（对待感情的态度、表达方式、敏感点）
   - 互动特点（如何回应他人、生气时的表现、开心时的表现）
3. 说话风格要自然，像真人而不是AI
4. 每个板块内容要充实，不要一句话带过
5. 总长度 800~1500 字，必须完整输出，不要截断$refPart$hintPart

直接输出人设内容，不要任何前缀或解释。"""

                Log.d("CreateCompanionVM", "开始AI生成人设，name=$name")
                val result = aiService.callOpenAiCompatibleForGeneration(prompt)
                Log.d("CreateCompanionVM", "AI生成结果长度=${result.length}")

                val cleaned = result
                    .removePrefix("```")
                    .removeSuffix("```")
                    .replace(Regex("^json\\s*", RegexOption.MULTILINE), "")
                    .trim()

                withContext(Dispatchers.Main) {
                    onResult(cleaned)
                }
            } catch (e: Exception) {
                Log.e("CreateCompanionVM", "AI生成人设失败", e)
                withContext(Dispatchers.Main) {
                    onResult("")
                }
            } finally {
                _isGenerating.value = false
                Log.d("CreateCompanionVM", "AI生成结束")
            }
        }
    }
}
