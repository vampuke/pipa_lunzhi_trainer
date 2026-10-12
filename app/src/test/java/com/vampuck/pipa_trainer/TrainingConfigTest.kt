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

    // ---------------- 保存列表的去重规则 ----------------

    @Test
    fun `mergeSaved replaces an entry with the same name`() {
        val list = listOf(cfg("甲"), cfg("乙").copy(rounds = listOf(TrainingPlan.Round(100, 60))))
        val after = TrainingConfig.mergeSaved(list, cfg("乙").copy(restSec = 45), 8)
        assertEquals(2, after.size)
        assertEquals(45, after[0].restSec)
        assertEquals("乙", after[0].name)
    }

    @Test
    fun `mergeSaved never drops an entry just because its plan matches`() {
        // 关键回归：以前按「同计划」去重，复制/载入后再保存会把**原件**顶掉。
        // 现在只按名字去重，同计划的两套（原件 + 副本）必须都在。
        val original = listOf(cfg("进阶练习"))
        val copy = cfg("进阶练习 副本")             // 计划完全相同
        val after = TrainingConfig.mergeSaved(original, copy, 8)
        assertEquals(2, after.size)
        assertEquals("进阶练习 副本", after[0].name)
        assertEquals("进阶练习", after[1].name)
        // 就算名字也不一样、计划也一样，仍然保留两条（合并只认名字）
        val samePlan = TrainingConfig.mergeSaved(after, cfg("又一套"), 8)
        assertEquals(3, samePlan.size)
    }

    @Test
    fun `mergeSaved trims to the limit keeping the newest`() {
        var list = emptyList<TrainingConfig>()
        for (i in 0 until 12) {
            list = TrainingConfig.mergeSaved(
                list, cfg("配置$i").copy(rounds = listOf(TrainingPlan.Round(60 + i, 60))), 8
            )
        }
        assertEquals(8, list.size)
        assertEquals("配置11", list[0].name)
        assertEquals("配置4", list[7].name)
    }

    // ---------------- 草稿（进页面自动记的那份）的收录规则 ----------------

    private fun twoRounds() = listOf(TrainingPlan.Round(60, 120), TrainingPlan.Round(84, 60))
    private fun oneRound(bpm: Int) = listOf(TrainingPlan.Round(bpm, 60))

    @Test
    fun `draft is inserted into an empty list so the section is never blank`() {
        // 进页面时列表为空，草稿要出现在「常用配置」里，否则用户以为这里没东西
        val out = TrainingConfig.mergeDraft(emptyList(), cfg("草稿"), emptySet(), 8)
        assertEquals(1, out.size)
        assertEquals("草稿", out[0].name)
    }

    @Test
    fun `draft cannot overwrite a config the user explicitly saved`() {
        val saved = listOf(cfg("我的练习"))
        val out = TrainingConfig.mergeDraft(
            saved, cfg("我的练习").copy(rounds = oneRound(120)), setOf("我的练习"), 8
        )
        assertTrue(out === saved)                                   // 原样返回，没被草稿改掉
        assertEquals(twoRounds(), out[0].rounds)
    }

    @Test
    fun `draft is skipped when another entry already has the same plan`() {
        val saved = listOf(cfg("我的练习"))
        val out = TrainingConfig.mergeDraft(
            saved, cfg("别的名字"), setOf("我的练习"), 8
        )
        assertTrue(out === saved)                                   // 不会出现两条同计划的
    }

    @Test
    fun `unnamed draft updates in place when its plan changes`() {
        val saved = listOf(cfg("草稿"))
        val out = TrainingConfig.mergeDraft(
            saved, cfg("草稿").copy(rounds = oneRound(120)), emptySet(), 8
        )
        assertEquals(1, out.size)
        assertEquals(oneRound(120), out[0].rounds)
    }

    @Test
    fun `draft with a different plan is added at the front`() {
        val saved = listOf(cfg("我的练习"))
        val out = TrainingConfig.mergeDraft(
            saved, cfg("新计划").copy(rounds = oneRound(120)), setOf("我的练习"), 8
        )
        assertEquals(2, out.size)
        assertEquals("新计划", out[0].name)
    }

    @Test
    fun `only one unnamed draft is ever kept`() {
        // 关键回归：未定名的草稿永远只占一条。用户调速度时自动名会跟着变
        // （60→65→70 BPM…），旧规则每次当新条目插入，8 次就把上限占满、把用户
        // 真正保存过的配置挤出列表。
        var list = listOf(cfg("我的练习"))
        var bpm = 60
        for (i in 0 until 12) {
            bpm += 5
            val draft = cfg("1 次 · $bpm BPM · 3 分").copy(rounds = oneRound(bpm))
            list = TrainingConfig.mergeDraft(list, draft, setOf("我的练习"), 8)
        }
        assertEquals(2, list.size)                                   // 用户那套 + 一条草稿
        assertEquals("我的练习", list[1].name)                        // 没有被挤出列表
        assertEquals("1 次 · 120 BPM · 3 分", list[0].name)           // 草稿是最新计划
        assertEquals(oneRound(120), list[0].rounds)
    }

    @Test
    fun `a second draft replaces the first even when it is not at index 0`() {
        val list = listOf(cfg("甲").copy(rounds = oneRound(60)), cfg("乙").copy(rounds = oneRound(72)))
        // 两条都未定名：只替换**第一条**草稿（列表里草稿本来最多一条，这条是脏数据兜底）
        val out = TrainingConfig.mergeDraft(list, cfg("丙").copy(rounds = oneRound(90)), emptySet(), 8)
        assertEquals(2, out.size)
        assertEquals("丙", out[0].name)
        assertEquals("乙", out[1].name)
    }

    @Test
    fun `a named config is never treated as the draft slot`() {
        val list = listOf(cfg("我的练习"), cfg("草稿").copy(rounds = oneRound(60)))
        val out = TrainingConfig.mergeDraft(
            list, cfg("草稿").copy(rounds = oneRound(96)), setOf("我的练习"), 8
        )
        assertEquals(2, out.size)
        assertEquals("我的练习", out[0].name)
        assertEquals(oneRound(96), out[1].rounds)                    // 还是就地更新
    }

    @Test
    fun `a draft never evicts a saved config when the list is full`() {
        // 8 条都已定名 → 草稿不进列表（当前计划存在 last_config 里，丢不了），
        // 而不是把最旧的那套挤出去
        val full = (0 until 8).map { cfg("配置$it").copy(rounds = oneRound(60 + it)) }
        val out = TrainingConfig.mergeDraft(
            full, cfg("新草稿").copy(rounds = oneRound(200)), full.map { it.name }.toSet(), 8
        )
        assertTrue(out === full)
        assertEquals(8, out.size)
        assertEquals("配置0", out[0].name)
    }
}
