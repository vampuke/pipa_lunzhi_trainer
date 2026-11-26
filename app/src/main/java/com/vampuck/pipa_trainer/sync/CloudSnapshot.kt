package com.vampuck.pipa_trainer.sync

import com.vampuck.pipa_trainer.practice.PracticeDay
import com.vampuck.pipa_trainer.practice.PracticeLog
import com.vampuck.pipa_trainer.practice.PracticeSource

data class CloudDay(val basicsMs: Long, val metronomeMs: Long, val freeMs: Long) {
    init {
        require(basicsMs >= 0 && metronomeMs >= 0 && freeMs >= 0)
        require(basicsMs <= Long.MAX_VALUE - metronomeMs)
        require(basicsMs + metronomeMs <= Long.MAX_VALUE - freeMs)
    }

    val totalMs: Long get() = basicsMs + metronomeMs + freeMs
}

data class CloudSnapshot(val userId: String, val fetchedAtMs: Long, val days: Map<String, CloudDay>) {
    fun toPracticeLog(): PracticeLog = PracticeLog().apply {
        for ((day, totals) in days) {
            put("cloud:$day:basics", PracticeSource.BASICS, day, totals.basicsMs)
            put("cloud:$day:metronome", PracticeSource.METRONOME, day, totals.metronomeMs)
            put("cloud:$day:free", PracticeSource.FREE, day, totals.freeMs)
        }
    }
}

/**
 * The private cache has a deliberately small JSON schema: ISO day -> three
 * nonnegative integer millisecond totals. No Android JSON runtime is needed to
 * read it, so cache corruption and display decisions can be tested on the JVM.
 * Network JSON is handled separately by AuthApi using org.json.
 */
object CloudCache {
    fun encode(days: Map<String, CloudDay>): String {
        require(days.keys.all(PracticeDay::isValid))
        return days.toSortedMap().entries.joinToString(",", "{", "}") { (day, value) ->
            "\"$day\":{\"basics\":${value.basicsMs},\"metronome\":${value.metronomeMs},\"free\":${value.freeMs}}"
        }
    }

    fun decode(json: String): Map<String, CloudDay>? = try {
        Reader(json).read()
    } catch (_: IllegalArgumentException) {
        null
    }

    private class Reader(private val json: String) {
        private var position = 0

        fun read(): Map<String, CloudDay> {
            val days = linkedMapOf<String, CloudDay>()
            expect('{')
            if (!take('}')) {
                do {
                    val day = key()
                    require(PracticeDay.isValid(day) && day !in days)
                    expect(':')
                    expect('{')
                    val values = linkedMapOf<String, Long>()
                    do {
                        val source = key()
                        require(source in setOf("basics", "metronome", "free") && source !in values)
                        expect(':')
                        skipSpace()
                        val start = position
                        while (position < json.length && json[position] in '0'..'9') position++
                        val number = json.substring(start, position)
                        require(number == "0" || (number.isNotEmpty() && number[0] != '0'))
                        values[source] = number.toLongOrNull() ?: throw IllegalArgumentException()
                    } while (take(','))
                    expect('}')
                    require(values.size == 3)
                    days[day] = CloudDay(values.getValue("basics"), values.getValue("metronome"), values.getValue("free"))
                } while (take(','))
                expect('}')
            }
            skipSpace()
            require(position == json.length)
            return days
        }

        private fun key(): String {
            expect('"')
            val start = position
            while (position < json.length && (json[position] in 'a'..'z' ||
                    json[position] in '0'..'9' || json[position] == '-')) position++
            val key = json.substring(start, position)
            require(position < json.length && json[position] == '"')
            position++
            return key
        }

        private fun skipSpace() {
            while (position < json.length && json[position] in " \n\r\t") position++
        }

        private fun take(char: Char): Boolean {
            skipSpace()
            if (position >= json.length || json[position] != char) return false
            position++
            return true
        }

        private fun expect(char: Char) { require(take(char)) }
    }
}
