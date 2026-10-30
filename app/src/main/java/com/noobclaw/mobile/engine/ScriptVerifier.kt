package com.noobclaw.mobile.engine

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest

/**
 * Verifies signed script packs (PRD §劇本安全 #1). Ed25519 via net.i2p.crypto:eddsa.
 *
 * Signed message = UTF-8 bytes of: "<scriptId>\n<version>\n<sha256(code) as lowercase hex>".
 * The backend only stores the signature; the private key lives offline (docs/SCRIPT_SIGNING.md).
 */
object ScriptVerifier {

    fun message(scriptId: String, version: String, code: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.UTF_8))
        return "$scriptId\n$version\n${digest.toHex()}".toByteArray(Charsets.UTF_8)
    }

    /** Returns false for any malformed input; never throws. */
    fun verify(pubKeyHex: String, scriptId: String, version: String, code: String, signatureHex: String): Boolean = runCatching {
        val pub = pubKeyHex.hexToBytes()
        val sig = signatureHex.hexToBytes()
        if (pub.size != 32 || sig.size != 64) return false
        // Small pure-Java Ed25519 (i2p eddsa) instead of BouncyCastle: keeps the APK small; works on Android 10+.
        val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val engine = EdDSAEngine(MessageDigest.getInstance(spec.hashAlgorithm))
        engine.initVerify(EdDSAPublicKey(EdDSAPublicKeySpec(pub, spec)))
        val msg = message(scriptId, version, code)
        engine.update(msg)
        engine.verify(sig)
    }.getOrDefault(false)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        val s = trim().lowercase()
        require(s.length % 2 == 0 && s.all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
