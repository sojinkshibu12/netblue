package com.example.meshtalk.security

import android.util.Base64
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.*
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    data class KeyPairData(val publicKey: String, val privateKey: String)

    fun generateX25519KeyPair(): KeyPairData {
        val kpg = KeyPairGenerator.getInstance("X25519", "BC")
        val keyPair = kpg.generateKeyPair()
        val publicKeyEncoded = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val privateKeyEncoded = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)
        return KeyPairData(publicKeyEncoded, privateKeyEncoded)
    }

    fun deriveSharedSecret(privateKeyBase64: String, publicKeyBase64: String): ByteArray {
        val privateBytes = Base64.decode(privateKeyBase64, Base64.NO_WRAP)
        val publicBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
        val keyFactory = KeyFactory.getInstance("X25519", "BC")
        val privateKey = keyFactory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privateBytes))
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicBytes))
        val keyAgreement = KeyAgreement.getInstance("X25519", "BC")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(publicKey, true)
        val sharedSecret = keyAgreement.generateSecret()
        return MessageDigest.getInstance("SHA-256").digest(sharedSecret)
    }

    fun encryptAESGCM(keyBytes: ByteArray, plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val spec = GCMParameterSpec(128, iv)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        val cipherText = cipher.doFinal(plainText.toByteArray())
        val output = iv + cipherText
        return Base64.encodeToString(output, Base64.NO_WRAP)
    }

    fun decryptAESGCM(keyBytes: ByteArray, cipherBase64: String): String {
        val allBytes = Base64.decode(cipherBase64, Base64.NO_WRAP)
        val iv = allBytes.copyOfRange(0, 12)
        val cipherText = allBytes.copyOfRange(12, allBytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return String(cipher.doFinal(cipherText))
    }
}