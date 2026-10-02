package com.yunian.ai.agent

import com.yunian.ai.domain.CapabilityDefaults
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CapabilityDefaults.requiresConfirm] 的真值表测试（纯 JVM）。
 *
 * 位置说明：[CapabilityDefaults] 定义在 core:domain（零依赖模块，**没有 junit 测试依赖项**，
 * 加测试依赖需要改 build 文件，本任务明令禁止），因此真值表测试放在已经具备 junit 的 :core:agent。
 *
 * 为什么值得单独钉死：这个三元判定是**唯一真相**，同时被两个消费者使用——
 * 装配期折叠（`AgentFacade.deriveToolCategory`）与设置页开关（`CapabilityGrantBoard.item`）。
 * 任何一档语义被改动，都会让「设置页显示的开关」与「装配期实际会不会拦」静默漂移。
 *
 * 三档语义（含「显式禁止一个本来不需要确认的安全工具」这条**两向**能力）：
 * - `true`  ⇒ 不需要确认；
 * - `false` ⇒ 需要确认；
 * - `null`  ⇒ 回到工具自身声明。
 */
class CapabilityDefaultsTest {

    @Test
    fun `显式允许压过工具自身的确认声明（两种工具默认都不需要确认）`() {
        assertFalse(
            "工具声明需要确认，但用户显式允许 ⇒ 不需要确认",
            CapabilityDefaults.requiresConfirm(explicit = true, toolRequiresConfirmation = true),
        )
        assertFalse(
            "工具本来就不需要确认，显式允许仍然是不需要确认",
            CapabilityDefaults.requiresConfirm(explicit = true, toolRequiresConfirmation = false),
        )
    }

    @Test
    fun `显式禁止压过工具自身的确认声明（安全工具也能被要求先确认）`() {
        assertTrue(
            "工具声明需要确认，显式禁止仍然需要确认",
            CapabilityDefaults.requiresConfirm(explicit = false, toolRequiresConfirmation = true),
        )
        assertTrue(
            "两向开关的关键一档：本来不需要确认的安全工具，用户也能改成必须先确认",
            CapabilityDefaults.requiresConfirm(explicit = false, toolRequiresConfirmation = false),
        )
    }

    @Test
    fun `没有显式决定时完全回到工具自身的声明`() {
        assertTrue(
            "无决定 + 工具声明需要确认 ⇒ 需要确认（fail-closed）",
            CapabilityDefaults.requiresConfirm(explicit = null, toolRequiresConfirmation = true),
        )
        assertFalse(
            "无决定 + 工具声明不需要确认 ⇒ 不需要确认（行为与引入授权表前逐字一致）",
            CapabilityDefaults.requiresConfirm(explicit = null, toolRequiresConfirmation = false),
        )
    }

    @Test
    fun `三档语义穷举：六种输入组合全部覆盖且互不冲突`() {
        val actual = listOf(
            Triple(null, true, true),
            Triple(null, false, false),
            Triple(true, true, false),
            Triple(true, false, false),
            Triple(false, true, true),
            Triple(false, false, true),
        ).associate { (explicit, toolDefault, expected) ->
            Triple(explicit, toolDefault, expected) to
                CapabilityDefaults.requiresConfirm(explicit, toolDefault)
        }

        val mismatches = actual.filter { (input, result) -> input.third != result }
        assertTrue(
            "真值表出现漂移：$mismatches",
            mismatches.isEmpty(),
        )
    }
}