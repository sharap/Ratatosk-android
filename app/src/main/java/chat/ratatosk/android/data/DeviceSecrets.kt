package chat.ratatosk.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Секрет устройства для базы аккаунта (`db_key`, §8.6).
 *
 * Тридцать два байта на аккаунт: ядро открывает базу ими — вместе с PIN
 * или вместо него. Байты лежат зашифрованными ключом из Android Keystore,
 * а тот наружу не выходит никогда, поэтому **на другом телефоне база не
 * откроется ни с PIN, ни без него**. Обратная сторона та же: телефон
 * потерян — переписка потеряна, и сказать об этом человеку надо до,
 * а не после.
 */
interface DeviceSecrets {
    /** Есть ли на этом устройстве рабочее хранилище ключей. */
    val isAvailable: Boolean

    /** Секрет аккаунта; `null` — его здесь нет. */
    fun get(accountIdHex: String): ByteArray?

    /** Заводит секрет и сохраняет его. Бросает, если сохранить не вышло. */
    fun create(accountIdHex: String): ByteArray

    fun forget(accountIdHex: String)
}

/** Поверх Android Keystore: ключ в железе, зашифрованные байты — рядом. */
class KeystoreSecrets(context: Context) : DeviceSecrets {
    private val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    override val isAvailable: Boolean
        get() = runCatching { key() }.isSuccess

    override fun get(accountIdHex: String): ByteArray? {
        val stored = prefs.getString(accountIdHex, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, IV_BYTES)
            val body = blob.copyOfRange(IV_BYTES, blob.size)
            Cipher.getInstance(TRANSFORM).run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
                doFinal(body)
            }
        } catch (t: Throwable) {
            // Ключ Keystore могли выбросить (сброс блокировки экрана,
            // перенос на другое устройство) — тогда секрета больше нет,
            // и притворяться, что есть, нельзя.
            android.util.Log.w(TAG, "Device secret is unreadable", t)
            null
        }
    }

    override fun create(accountIdHex: String): ByteArray {
        val secret = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val body = cipher.doFinal(secret)
        val blob = cipher.iv + body
        // Сначала на диск, потом наружу: база, заведённая незаписанным
        // ключом, не откроется больше никогда.
        val saved = prefs.edit().putString(accountIdHex, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
        check(saved) { "device secret was not stored" }
        return secret
    }

    override fun forget(accountIdHex: String) {
        prefs.edit().remove(accountIdHex).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Свой вектор не задаём: повтор пары «ключ, вектор» в GCM
                // ломает шифр, и пусть за этим следит система.
                .setRandomizedEncryptionRequired(true)
                // Без подтверждения блокировкой: смысл секрета в том, чтобы
                // аккаунт открывался сам — например, службе после перезапуска.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "RatatoskVM"
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "ratatosk.device.secret"
        const val STORE = "device_secrets"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
