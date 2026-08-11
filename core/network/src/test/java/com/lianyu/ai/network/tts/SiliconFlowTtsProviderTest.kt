package com.lianyu.ai.network.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 硅基流动音色列表响应解析测试。
 *
 * 实测 `/v1/audio/voice/list` 返回键是 `result`（单数）：
 * `{"result":[{"customName":"dp_42824","uri":"speech:...","text":"...","model":"..."}]}`
 * 之前按文档误写 `results` 导致名称→URI 解析恒失败 → 合成 400 "Invalid voice"。
 */
class SiliconFlowTtsProviderTest {

    private val realResponse = """
        {
          "result": [
            {
              "model": "FunAudioLLM/CosyVoice2-0.5B",
              "customName": "dp_42824",
              "text": "指挥家，可以占用你一些时间吗",
              "uri": "speech:dp_42824:d60c969719ns73f85u70:itkuhmysngpxdtoldgww"
            },
            {
              "model": "FunAudioLLM/CosyVoice2-0.5B",
              "customName": "dp_34249",
              "text": "test",
              "uri": "speech:dp_34249:d60c969719ns73f85u70:gwxelyiwjzmnptsdmrwf"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun realResultKey_resolvesByCustomName() {
        assertEquals(
            "speech:dp_42824:d60c969719ns73f85u70:itkuhmysngpxdtoldgww",
            SiliconFlowTtsProvider.resolveVoiceUriFromJson(realResponse, "dp_42824")
        )
    }

    @Test
    fun realResultKey_resolvesByUri() {
        assertEquals(
            "speech:dp_34249:d60c969719ns73f85u70:gwxelyiwjzmnptsdmrwf",
            SiliconFlowTtsProvider.resolveVoiceUriFromJson(
                realResponse,
                "speech:dp_34249:d60c969719ns73f85u70:gwxelyiwjzmnptsdmrwf"
            )
        )
    }

    @Test
    fun nameNotFound_returnsNull() {
        assertNull(SiliconFlowTtsProvider.resolveVoiceUriFromJson(realResponse, "dp_99999"))
    }

    @Test
    fun legacyResultsKey_stillResolves() {
        // 早期文档的 `results` 键也要兼容（数组包裹）
        val legacy = """{"results": [{"customName":"dp_42824","uri":"speech:legacy:xxx","text":"t","model":"m"}]}"""
        assertEquals(
            "speech:legacy:xxx",
            SiliconFlowTtsProvider.resolveVoiceUriFromJson(legacy, "dp_42824")
        )
    }

    @Test
    fun malformedJson_returnsNull() {
        assertNull(SiliconFlowTtsProvider.resolveVoiceUriFromJson("not json", "dp_42824"))
    }

    @Test
    fun emptyUri_returnsNull() {
        val empty = """{"result":[{"customName":"dp_x","uri":"","text":"t","model":"m"}]}"""
        assertNull(SiliconFlowTtsProvider.resolveVoiceUriFromJson(empty, "dp_x"))
    }
}
