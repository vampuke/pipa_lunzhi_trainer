package com.vampuck.pipa_trainer.data

import android.content.Context
import android.content.SharedPreferences
import com.vampuck.pipa_trainer.training.TrainingConfig

/**
 * 指力训练配置的持久化。两组数据：
 *  - `last`：**上一次编辑的配置**（每次改动/开始/离开都会存），下次进入自动恢复；
 *  - `saved`：可复用的多套配置列表，按最近使用排在前面，供快捷选择。
 *
 * 每套配置是 [TrainingConfig.encode] 出的一行文本，多套之间用换行分隔
 * （名字里的换行在存之前已被 [TrainingConfig.sanitize] 换掉）。
 */
object TrainingConfigStore {

    private const val PREF = "strength_training"
    private const val KEY_LAST = "last_config"
    private const val KEY_SAVED = "saved_configs"
    /** 用户显式保存/复制过的名字（草稿不得覆盖这些条目）。 */
    private const val KEY_NAMED = "named_configs"

    /** 列表上限：超出就丢掉最旧的，避免越攒越长。 */
    const val MAX_SAVED = 8

    fun last(ctx: Context): TrainingConfig? =
        prefs(ctx).getString(KEY_LAST, null)?.let { TrainingConfig.decode(it) }

    fun saved(ctx: Context): List<TrainingConfig> =
        prefs(ctx).getString(KEY_SAVED, "").orEmpty()
            .split('\n')
            .mapNotNull { if (it.isBlank()) null else TrainingConfig.decode(it) }

    /** 一套配置是否已经「定名」——定名 = 用户显式保存/复制过，草稿不能再覆盖它。 */
    fun isNamed(ctx: Context, name: String): Boolean =
        named(ctx).contains(name)

    private fun named(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_NAMED, emptySet()) ?: emptySet()

    private fun markNamed(ctx: Context, name: String) {
        prefs(ctx).edit().putStringSet(KEY_NAMED, named(ctx) + name).apply()
    }

    /**
     * 「上一次编辑的配置」+「正在编辑的草稿进列表」的合并版——**这两件事本来就是
     * 同一个 draft**，分两次 [SharedPreferences.Editor.apply] 写下去只是白跑一趟盘，
     * 而且中途被回收会留下「last 已更新、列表还没更新」的半截状态。合成一次原子写。
     *
     * 刚进页面时常用配置不该是空的——用户会以为这里没东西、不知道能新增。所以把
     * 当前这份草稿也放进去。**已定名**的那套（用户显式保存过的）不会被草稿覆盖。
     */
    fun saveLastAndRecordDraft(ctx: Context, draft: TrainingConfig): List<TrainingConfig> {
        val current = saved(ctx)
        val out = TrainingConfig.mergeDraft(current, draft, named(ctx), MAX_SAVED)
        // mergeDraft 无需改动时按引用返回传入的 current；用同一个 current 比较，
        // 不要再调一次 saved(ctx)（那会生成新 List 让 !== 恒真，每次都白写一遍盘）。
        val edit = prefs(ctx).edit().putString(KEY_LAST, draft.encode())
        if (out !== current) {
            edit.putString(KEY_SAVED, encodeList(out))
            pruneNamed(ctx, edit, out)
        }
        edit.apply()
        return out
    }

    /**
     * 收录一套配置（**只按名字去重**）。
     *
     * 刻意不按「同计划」去重：复制出来的那套在用户改之前与原计划完全相同，
     * 若按 [TrainingConfig.samePlanAs] 去重，就会把**原来那套顶掉**——用户会看到
     * 自己刚复制的东西把原件吃了（v1.22 的真 bug）。「同计划不重复」交给
     * [saveLastAndRecordDraft]（草稿入库时判断），显式保存/开始训练走这里，一律按名字收录。
     */
    fun putDistinct(ctx: Context, config: TrainingConfig): List<TrainingConfig> {
        val list = insert(ctx, config)
        markNamed(ctx, config.name)     // 显式保存/复制 → 从此这份不再被草稿覆盖
        // 显式保存后，把与它计划相同、且从未定名的**草稿**清掉：
        // 进页面自动记的那条草稿就是同一份计划，留着只会让人看到重复的一条。
        val cleaned = saved(ctx).filterNot {
            it.name != config.name && !isNamed(ctx, it.name) && it.samePlanAs(config)
        }
        persist(ctx, cleaned)
        return cleaned
    }

    private fun insert(ctx: Context, config: TrainingConfig): List<TrainingConfig> {
        val list = TrainingConfig.mergeSaved(saved(ctx), config, MAX_SAVED)
        persist(ctx, list)
        return list
    }

    fun delete(ctx: Context, name: String): List<TrainingConfig> {
        val list = saved(ctx).filter { it.name != name }
        // 名字从列表里消失，KEY_NAMED 里的那道标记由 pruneNamed 顺手剪掉。
        persist(ctx, list)
        // 「上一次编辑的配置」如果就是被删的这套，也要一起清掉：否则下次进页面
        // restoreLastConfig() 会把它恢复出来、再录进列表——用户删掉的配置自己回来了。
        if (last(ctx)?.name == name) prefs(ctx).edit().remove(KEY_LAST).apply()
        return list
    }

    private fun persist(ctx: Context, list: List<TrainingConfig>) {
        val edit = prefs(ctx).edit().putString(KEY_SAVED, encodeList(list))
        pruneNamed(ctx, edit, list)
        edit.apply()
    }

    private fun encodeList(list: List<TrainingConfig>): String =
        list.joinToString("\n") { it.encode() }

    /**
     * 让 [KEY_NAMED] 跟着 [KEY_SAVED] 走：列表里已经没有的名字，标记也不该留着。
     *
     * 不剪的话这集合只增不减——删掉配置后名字还在，下次撞上同名的新配置会被
     * [isNamed] 误判成「用户显式保存过」，于是草稿覆盖规则（以及列表里那行
     * 「编辑这套配置」）跟着失真。名字的**唯一来源**就是列表本身，所以每次写列表
     * 顺手剪一次，比在每个删除/淘汰路径上各补一刀可靠。
     */
    private fun pruneNamed(
        ctx: Context,
        edit: SharedPreferences.Editor,
        list: List<TrainingConfig>
    ) {
        val keep = list.mapTo(HashSet()) { it.name }
        if (!keep.containsAll(named(ctx))) edit.putStringSet(KEY_NAMED, keep)
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
