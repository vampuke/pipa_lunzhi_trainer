package com.vampuck.pipa_trainer.data

import android.content.Context
import com.vampuck.pipa_trainer.MetronomeActivity

/** 独立节拍器的上次速度；缺失、类型错误或越界时使用默认值。 */
object MetronomePrefs {
    const val DEFAULT_BPM = 80
    private const val PREF = "metronome"
    private const val KEY_LAST_BPM = "last_bpm"

    fun bpm(ctx: Context, def: Int = DEFAULT_BPM): Int {
        val fallback = def.coerceIn(MetronomeActivity.MIN_BPM, MetronomeActivity.MAX_BPM)
        val saved = runCatching { prefs(ctx).getInt(KEY_LAST_BPM, fallback) }
            .getOrDefault(fallback)
        return saved.takeIf { it in MetronomeActivity.MIN_BPM..MetronomeActivity.MAX_BPM }
            ?: fallback
    }

    fun saveBpm(ctx: Context, bpm: Int) {
        val value = bpm.coerceIn(MetronomeActivity.MIN_BPM, MetronomeActivity.MAX_BPM)
        prefs(ctx).edit().putInt(KEY_LAST_BPM, value).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
