package com.noobclaw.mobile.engine

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest

/**
 * Verifies signed script packs (PRD §劇本安全 #1).
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
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(pub, 0))
        val msg = message(scriptId, version, code)
        signer.update(msg, 0, msg.size)
        signer.verifySignature(sig)
    }.getOrDefault(false)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        val s = trim().lowercase()
        require(s.length % 2 == 0 && s.all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
