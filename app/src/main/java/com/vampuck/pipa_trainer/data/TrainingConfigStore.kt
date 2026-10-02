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

    /**
     * 收录一套配置：同名或同计划（[TrainingConfig.samePlanAs]）的先去掉，
     * 再插到最前面——所以「开始过的配置」会自然出现在快捷列表里，且不会重复。
     */
    fun put(ctx: Context, config: TrainingConfig): List<TrainingConfig> {
        val list = saved(ctx).toMutableList()
        list.removeAll { it.name == config.name || it.samePlanAs(config) }
        list.add(0, config)
        while (list.size > MAX_SAVED) list.removeAt(list.size - 1)
        persist(ctx, list)
        return list
    }

    fun delete(ctx: Context, name: String): List<TrainingConfig> {
        val list = saved(ctx).filter { it.name != name }
        persist(ctx, list)
        return list
    }

    private fun persist(ctx: Context, list: List<TrainingConfig>) {
        prefs(ctx).edit().putString(KEY_SAVED, list.joinToString("\n") { it.encode() }).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
