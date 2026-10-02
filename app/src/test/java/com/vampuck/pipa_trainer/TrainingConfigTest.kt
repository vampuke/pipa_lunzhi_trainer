package com.vampuck.pipa_trainer

import com.vampuck.pipa_trainer.training.TrainingConfig
import com.vampuck.pipa_trainer.training.TrainingPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指力训练配置的保存/读回。它是「记住上次配置 + 多套配置快捷选择」的全部持久化逻辑，
 * 存的是自定义的一行文本，所以边界（名字带分隔符、数值越界、脏数据）必须逐个钉住。
 */
class TrainingConfigTest {

    private fun cfg(name: String = "日常") = TrainingConfig(
        name = name,
        rounds = listOf(TrainingPlan.Round(60, 120), TrainingPlan.Round(84, 60)),
        restSec = 30,
        guidePerBeat = true,
        accentFirst = false,
        useMic = true
    )

    @Test
    fun `encode decode round trip`() {
        val c = cfg()
        val back = TrainingConfig.decode(c.encode())
        assertNotNull(back)
        back!!
        assertEquals(c.name, back.name)
        assertEquals(c.rounds, back.rounds)
        assertEquals(c.restSec, back.restSec)
        assertEquals(c.guidePerBeat, back.guidePerBeat)
        assertEquals(c.accentFirst, back.accentFirst)
        assertEquals(c.useMic, back.useMic)
    }

    @Test
    fun `encoded text is a single line`() {
        val text = cfg().encode()
        assertFalse(text.contains('\n'))
        assertEquals("30|101|60:120,84:60|日常", text)
    }

    @Test
    fun `name with separators and newlines is sanitized`() {
        val text = cfg("我的|配置\n第二行").encode()
        assertEquals(4, text.split('|').size)          // 名字里的 | 被换掉，仍是 4 段
        val back = TrainingConfig.decode(text)!!
        assertEquals("我的 配置 第二行", back.name)
    }

    @Test
    fun `long names are truncated`() {
        val long = "一二三四五六七八九十一二三四五六七八九十一二三四五"
        val back = TrainingConfig.decode(cfg(long).encode())!!
        assertEquals(TrainingConfig.MAX_NAME_LEN, back.name.length)
    }

    @Test
    fun `out of range values are clamped on decode`() {
        val back = TrainingConfig.decode("999|100|9999:100000,5:0|怪配置")!!
        assertEquals(TrainingPlan.MAX_REST_SEC, back.restSec)
        assertEquals(TrainingPlan.MAX_BPM, back.rounds[0].bpm)
        assertEquals(TrainingPlan.MAX_DURATION_SEC, back.rounds[0].durationSec)
        assertEquals(TrainingPlan.MIN_BPM, back.rounds[1].bpm)
        assertEquals(TrainingPlan.MIN_DURATION_SEC, back.rounds[1].durationSec)
    }

    @Test
    fun `garbage decodes to null`() {
        assertNull(TrainingConfig.decode(""))
        assertNull(TrainingConfig.decode("30|100|60:120"))
        assertNull(TrainingConfig.decode("abc|100|60:120|名字"))
        assertNull(TrainingConfig.decode("30|100||名字"))
        assertNull(TrainingConfig.decode("30|100|60:abc|名字"))
        assertNull(TrainingConfig.decode("30|10|60:120|名字"))
    }

    @Test
    fun `applyTo copies rounds and rest into the plan`() {
        val plan = TrainingPlan(rounds = mutableListOf(TrainingPlan.Round(200, 30)))
        cfg().applyTo(plan)
        assertEquals(2, plan.size)
        assertEquals(60, plan.rounds[0].bpm)
        assertEquals(84, plan.rounds[1].bpm)
        assertEquals(120, plan.rounds[0].durationSec)
        assertEquals(30, plan.restSec)
        // 改配置里的轮次不应影响已载入的计划
        plan.rounds[0].bpm = 100
        assertEquals(60, cfg().rounds[0].bpm)
    }

    @Test
    fun `samePlanAs ignores the name`() {
        val a = cfg("甲")
        val b = cfg("乙")
        assertTrue(a.samePlanAs(b))
        assertFalse(a.samePlanAs(b.copy(restSec = 60)))
        assertFalse(a.samePlanAs(b.copy(accentFirst = true)))
        assertFalse(a.samePlanAs(b.copy(rounds = listOf(TrainingPlan.Round(60, 90)))))
    }

    @Test
    fun `auto name describes the plan`() {
        val same = listOf(TrainingPlan.Round(60, 120), TrainingPlan.Round(60, 120))
        assertEquals("2 次 · 60 BPM · 5 分", TrainingConfig.autoName(same, 30, 5))

        val ramp = listOf(TrainingPlan.Round(60, 120), TrainingPlan.Round(84, 60))
        assertEquals("2 次 · 60→84 BPM · 4 分", TrainingConfig.autoName(ramp, 30, 5))

        assertEquals("空计划", TrainingConfig.autoName(emptyList(), 30, 5))
    }
}
