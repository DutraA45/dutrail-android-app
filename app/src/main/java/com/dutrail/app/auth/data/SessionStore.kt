package com.dutrail.app.auth.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.dutrail.app.auth.domain.Session
import com.dutrail.app.core.network.ApiJson
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

interface SessionStore {
    /** Emite null quando não há sessão (nunca logou, logout, ou refresh recusado). */
    val session: Flow<Session?>
    suspend fun current(): Session?
    /** Grava o par inteiro de uma vez: nunca fica um access novo com refresh antigo. */
    suspend fun save(session: Session)
    suspend fun clear()
}

/**
 * Sessão num único arquivo do DataStore, cifrado com chave do Android Keystore ([TokenCipher]).
 * O DataStore grava num arquivo temporário e renomeia, então cada [save] é atômico.
 */
class EncryptedSessionStore(private val dataStore: DataStore<SessionFile>) : SessionStore {

    override val session: Flow<Session?> =
        dataStore.data.map { it.session?.toDomain() }.distinctUntilChanged()

    override suspend fun current(): Session? = session.first()

    override suspend fun save(session: Session) {
        dataStore.updateData { SessionFile(StoredSession(session.accessToken, session.refreshToken, session.userId)) }
    }

    override suspend fun clear() {
        dataStore.updateData { SessionFile() }
    }
}

@Serializable
data class SessionFile(val session: StoredSession? = null)

@Serializable
data class StoredSession(val accessToken: String, val refreshToken: String, val userId: String) {
    fun toDomain() = Session(accessToken, refreshToken, userId)
}

class SessionFileSerializer(private val cipher: TokenCipher) : Serializer<SessionFile> {
    override val defaultValue = SessionFile()

    override suspend fun readFrom(input: InputStream): SessionFile {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return defaultValue
        return try {
            ApiJson.decodeFromString(cipher.decrypt(bytes).decodeToString())
        } catch (e: GeneralSecurityException) {
            // Chave do Keystore perdida/invalidada (ex.: restauração em outro aparelho): exige novo login.
            throw CorruptionException("Não foi possível decifrar a sessão", e)
        } catch (e: SerializationException) {
            throw CorruptionException("Sessão ilegível", e)
        }
    }

    override suspend fun writeTo(t: SessionFile, output: OutputStream) {
        output.write(cipher.encrypt(ApiJson.encodeToString(SessionFile.serializer(), t).encodeToByteArray()))
    }
}
