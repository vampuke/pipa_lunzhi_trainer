package com.vampuck.pipa_trainer.data

import android.content.Context
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

    fun saveLast(ctx: Context, config: TrainingConfig) {
        prefs(ctx).edit().putString(KEY_LAST, config.encode()).apply()
    }

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
     * 记录「正在编辑的草稿」进列表。
     *
     * 刚进页面时常用配置不该是空的——用户会以为这里没东西、不知道能新增。所以把
     * 当前这份草稿也放进去。**已定名**的那套（用户显式保存过的）不会被草稿覆盖。
     */
    fun recordDraft(ctx: Context, draft: TrainingConfig): List<TrainingConfig> {
        val out = TrainingConfig.mergeDraft(saved(ctx), draft, named(ctx), MAX_SAVED)
        if (out !== saved(ctx)) persist(ctx, out)
        return out
    }

    /**
     * 收录一套配置：同名或同计划（[TrainingConfig.samePlanAs]）的先去掉，
     * 再插到最前面——所以「开始过的配置」会自然出现在快捷列表里，且不会重复。
     */
    fun put(ctx: Context, config: TrainingConfig): List<TrainingConfig> =
        insert(ctx, config, dedupeByPlan = true)

    /**
     * 收录**不去重同计划**的一套（只按名字去重）。
     *
     * 「复制为新的」用这个：复制出来的那套在用户改之前与原计划完全相同，
     * 若按 [TrainingConfig.samePlanAs] 去重，就会把**原来那套顶掉**——用户会
     * 看到自己刚复制的东西把原件吃了。所以复制走这里。
     */
    fun putDistinct(ctx: Context, config: TrainingConfig): List<TrainingConfig> {
        val list = insert(ctx, config, dedupeByPlan = false)
        markNamed(ctx, config.name)     // 显式保存/复制 → 从此这份不再被草稿覆盖
        // 显式保存后，把与它计划相同、且从未定名的**草稿**清掉：
        // 进页面自动记的那条草稿就是同一份计划，留着只会让人看到重复的一条。
        val cleaned = saved(ctx).filterNot {
            it.name != config.name && !isNamed(ctx, it.name) && it.samePlanAs(config)
        }
        persist(ctx, cleaned)
        return cleaned
    }

    private fun insert(
        ctx: Context, config: TrainingConfig, dedupeByPlan: Boolean
    ): List<TrainingConfig> {
        val list = TrainingConfig.mergeSaved(saved(ctx), config, dedupeByPlan, MAX_SAVED)
        persist(ctx, list)
        return list
    }

    fun delete(ctx: Context, name: String): List<TrainingConfig> {
        val list = saved(ctx).filter { it.name != name }
        persist(ctx, list)
        if (isNamed(ctx, name)) {
            prefs(ctx).edit().putStringSet(KEY_NAMED, named(ctx) - name).apply()
        }
        return list
    }

    private fun persist(ctx: Context, list: List<TrainingConfig>) {
        prefs(ctx).edit().putString(KEY_SAVED, list.joinToString("\n") { it.encode() }).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
