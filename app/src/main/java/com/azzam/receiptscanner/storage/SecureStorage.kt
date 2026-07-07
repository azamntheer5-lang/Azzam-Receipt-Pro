package com.azzam.receiptscanner.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * تشفير/فك تشفير ملف واحد بمفتاح AES-256-GCM محفوظ داخل Android Keystore
 * (محمي بالهاردوير في أغلب الأجهزة الحديثة).
 *
 * لماذا بهذه الطريقة وليس عبر SQLCipher أو Jetpack Security؟
 * كلا الخيارين مرّا بتغييرات كبيرة مؤخراً (SQLCipher غيّر اسم حزمته،
 * و androidx.security:security-crypto أصبحت deprecated رسمياً) وهذا يجعل
 * الاعتماد عليهما في كود غير مُختبَر بالتصريف الفعلي مخاطرة غير ضرورية.
 * هذا الحل يعتمد فقط على android.security.keystore وjavax.crypto، وهي
 * APIs منصة مستقرة لم تتغيّر منذ سنوات.
 */
object SecureStorage {

    private const val KEY_ALIAS = "receipt_scanner_master_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    @Synchronized
    fun encryptAndWrite(file: File, plainText: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // التنسيق المخزَّن: [طول الـIV: byte واحد][IV][النص المشفَّر] ثم الكل بصيغة Base64
        val combined = ByteArray(1 + iv.size + cipherText.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(cipherText, 0, combined, 1 + iv.size, cipherText.size)

        file.writeBytes(Base64.encode(combined, Base64.NO_WRAP))
    }

    @Synchronized
    fun readAndDecrypt(file: File): String? {
        if (!file.exists() || file.length() == 0L) return null

        return try {
            val combined = Base64.decode(file.readBytes(), Base64.NO_WRAP)
            val ivLength = combined[0].toInt()
            val iv = combined.copyOfRange(1, 1 + ivLength)
            val cipherText = combined.copyOfRange(1 + ivLength, combined.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            )
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            // ملف تالف أو مفتاح غير متطابق - لا نكسر التطبيق، فقط نتعامل معه كأنه فارغ
            null
        }
    }
}
