package com.azzam.receiptscanner.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.azzam.receiptscanner.storage.TransferRepository
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * نسخ احتياطي محمي بكلمة مرور يختارها المستخدم (وليس مفتاح Keystore الخاص
 * بالتطبيق) - هذا يجعل النسخة قابلة للاستعادة على جهاز آخر أو بعد تهيئة
 * الهاتف، بعكس التشفير الأساسي للتطبيق المرتبط بجهاز واحد فقط.
 */
object BackupManager {
    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_SIZE = 16

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    fun writeBackup(context: Context, destUri: Uri, password: String): Boolean {
        return try {
            val json = TransferRepository.rawJsonForBackup()
            val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
            val key = deriveKey(password, salt)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(json.toByteArray(Charsets.UTF_8))

            // التنسيق: [salt: 16 بايت][طول IV: بايت واحد][IV][النص المشفّر] ثم الكل Base64
            val combined = ByteArray(SALT_SIZE + 1 + iv.size + cipherText.size)
            System.arraycopy(salt, 0, combined, 0, SALT_SIZE)
            combined[SALT_SIZE] = iv.size.toByte()
            System.arraycopy(iv, 0, combined, SALT_SIZE + 1, iv.size)
            System.arraycopy(cipherText, 0, combined, SALT_SIZE + 1 + iv.size, cipherText.size)

            context.contentResolver.openOutputStream(destUri)?.use { out ->
                out.write(Base64.encode(combined, Base64.NO_WRAP))
            } ?: return false
            true
        } catch (e: Exception) {
            false
        }
    }

    /** يستبدل كل البيانات الحالية بمحتوى النسخة الاحتياطية - استخدم بحذر. */
    fun restoreBackup(context: Context, sourceUri: Uri, password: String): Boolean {
        return try {
            val bytes = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                ?: return false
            val combined = Base64.decode(bytes, Base64.NO_WRAP)

            val salt = combined.copyOfRange(0, SALT_SIZE)
            val ivLength = combined[SALT_SIZE].toInt()
            val iv = combined.copyOfRange(SALT_SIZE + 1, SALT_SIZE + 1 + ivLength)
            val cipherText = combined.copyOfRange(SALT_SIZE + 1 + ivLength, combined.size)

            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val json = String(cipher.doFinal(cipherText), Charsets.UTF_8)

            val transfers = TransferRepository.parseBackupJson(json)
            TransferRepository.replaceAll(context, transfers)
            true
        } catch (e: Exception) {
            false // كلمة مرور خاطئة أو ملف تالف - فشل بهدوء
        }
    }
}
