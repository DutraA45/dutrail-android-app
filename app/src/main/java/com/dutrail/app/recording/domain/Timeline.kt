package com.dutrail.app.recording.domain

/** Tempo de cronômetro (ativo) a partir dos eventos START/PAUSE/RESUME/STOP, em ordem cronológica. */
object Timeline {
    fun activeMillis(events: List<TimerEvent>, atMillis: Long): Long {
        var total = 0L
        var runningSince: Long? = null
        for (event in events) {
            if (event.timeMillis > atMillis) break
            when (event.type) {
                TimerEventType.START, TimerEventType.RESUME -> if (runningSince == null) runningSince = event.timeMillis
                TimerEventType.PAUSE, TimerEventType.STOP -> runningSince?.let {
                    total += event.timeMillis - it
                    runningSince = null
                }
            }
        }
        runningSince?.let { total += atMillis - it }
        return total
    }
}
