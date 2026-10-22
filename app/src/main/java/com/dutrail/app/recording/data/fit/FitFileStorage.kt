package com.dutrail.app.recording.data.fit

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Arquivos .fit gerados, em filesDir/fit (privado do app, fora do cache: o sistema não apaga). */
@Singleton
class FitFileStorage @Inject constructor(@ApplicationContext context: Context) {

    private val dir = File(context.filesDir, "fit")
    private val prefs = context.getSharedPreferences("fit", Context.MODE_PRIVATE)

    fun file(name: String) = File(dir, name)

    /** Grava num temporário com fsync e renomeia: nunca fica um .fit pela metade com o nome final. */
    fun write(name: String, bytes: ByteArray): File {
        dir.mkdirs()
        val target = file(name)
        val tmp = File(dir, "$name.tmp")
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.fd.sync()
        }
        if (!tmp.renameTo(target)) throw IOException("Não foi possível salvar $name")
        return target
    }

    /** Serial do "dispositivo" no file_id: aleatório e fixo por instalação. */
    @Synchronized
    fun installSerialNumber(): Long {
        prefs.getLong(KEY_SERIAL, 0L).takeIf { it != 0L }?.let { return it }
        return Random.nextLong(1L, 0xFFFF_FFFFL).also { prefs.edit { putLong(KEY_SERIAL, it) } }
    }

    private companion object {
        const val KEY_SERIAL = "serial_number"
    }
}
