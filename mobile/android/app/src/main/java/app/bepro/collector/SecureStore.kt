package app.bepro.collector

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
 * Small Keystore-backed string store for the pairing secret and JWT.
 * AES-GCM key lives in the Android Keystore; ciphertext lives in SharedPreferences.
 */
class SecureStore(context: Context) {

  private val prefs = context.getSharedPreferences("bepro_secure", Context.MODE_PRIVATE)

  fun put(name: String, value: String?) {
    if (value == null) {
      prefs.edit().remove(name).apply()
      return
    }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
      Base64.encodeToString(encrypted, Base64.NO_WRAP)
    prefs.edit().putString(name, packed).apply()
  }

  fun get(name: String): String? {
    val packed = prefs.getString(name, null) ?: return null
    return try {
      val (iv, data) = packed.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
      String(cipher.doFinal(data), Charsets.UTF_8)
    } catch (e: Exception) {
      // Key was wiped (e.g. lock-screen change on some ROMs): treat as missing so the user re-pairs.
      prefs.edit().remove(name).apply()
      null
    }
  }

  fun clear() = prefs.edit().clear().apply()

  private fun key(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build(),
    )
    return generator.generateKey()
  }

  private companion object {
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val ALIAS = "bepro_secure_v1"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
  }
}
