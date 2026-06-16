package com.lianyu.ai.network.tts

enum class TtsProvider(val displayName: String, val description: String) {
    ANDROID("系统TTS", "使用系统内置语音引擎，无需配置"),
    ALIYUN("阿里云", "阿里云语音合成 - 多种音色可选"),
    BAIDU("百度", "百度语音合成 - 中文效果好"),
    XUNFEI("讯飞", "讯飞语音 - 情感丰富"),
    MICROSOFT("微软Azure", "Azure TTS - 多语言神经语音"),
    VOLCENGINE("火山引擎", "豆包语音合成大模型 - 高拟真音色")
}