package com.appcustom.whitelistbrowser

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Privacy: sealed messages.
 *
 * - This phone's own key pair lives in Android's secure key storage; its private half never leaves it (not
 *   even the app can read it out). The public half is sent when the phone registers. GitHub seals this
 *   phone's lists and answers with it, so only this phone can read them.
 * - Requests are sealed with the request key's public half (built into the app, [BuildConfig.REQUEST_KEY]),
 *   so only GitHub's automation, which has the private half, can read them.
 *
 * A sealed message: {"v":1, "k": the AES key locked with RSA-OAEP (SHA-1), "iv": 12 bytes,
 * "d": the text locked with AES-256-GCM, with its 16-byte check tag at the end}. The same as GitHub's side.
 */
object Seal {
    private const val ALIAS = "wlb-phone"
    private const val RSA = "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"

    /** Is the app built with the request key (so requests can be sealed)? */
    val canSeal: Boolean get() = BuildConfig.REQUEST_KEY.isNotEmpty()

    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** This phone's public key (base64), made the first time it's needed. */
    @Synchronized fun publicKey(): String {
        val ks = store()
        if (!ks.containsAlias(ALIAS)) {
            val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            g.initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .build())
            g.generateKeyPair()
        }
        return Base64.encodeToString(ks.getCertificate(ALIAS).publicKey.encoded, Base64.NO_WRAP)
    }

    /** Seals [obj] so only the holder of [publicKeyB64]'s private half can read it (by default: GitHub). */
    fun seal(obj: JSONObject, publicKeyB64: String = BuildConfig.REQUEST_KEY): JSONObject {
        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(publicKeyB64, Base64.DEFAULT)))
        val aes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv))
        val d = c.doFinal(obj.toString().toByteArray(Charsets.UTF_8))
        val w = Cipher.getInstance(RSA)
        w.init(Cipher.ENCRYPT_MODE, pub)
        val k = w.doFinal(aes)
        fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        return JSONObject().put("v", 1).put("k", b64(k)).put("iv", b64(iv)).put("d", b64(d))
    }

    /** Opens something sealed for this phone. Throws if it can't (not for this phone, or changed on the way). */
    fun open(sealed: JSONObject): JSONObject {
        val priv = store().getKey(ALIAS, null) as? PrivateKey ?: throw IllegalStateException("no key on this phone")
        val w = Cipher.getInstance(RSA)
        w.init(Cipher.DECRYPT_MODE, priv)
        val aes = w.doFinal(Base64.decode(sealed.getString("k"), Base64.DEFAULT))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, Base64.decode(sealed.getString("iv"), Base64.DEFAULT)))
        return JSONObject(String(c.doFinal(Base64.decode(sealed.getString("d"), Base64.DEFAULT)), Charsets.UTF_8))
    }

    /** The name of this phone's sealed bundle in the public repository (made from its ID, without showing it). */
    fun bundleName(id: String): String =
        MessageDigest.getInstance("SHA-256").digest("wlb:$id".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(32)

    /** A request's hidden part for its GitHub text: sealed, so only GitHub's automation can read it. */
    fun hiddenPart(obj: JSONObject): String = "<!-- whitelist-sealed\n${seal(obj)}\n-->"
}
