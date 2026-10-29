package com.noobclaw.mobile

import com.noobclaw.mobile.engine.ScriptVerifier
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class ScriptVerifierTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val kp = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }.generateKeyPair()
    private val pubHex = hex((kp.public as Ed25519PublicKeyParameters).encoded)

    private fun sign(id: String, ver: String, code: String): String {
        val s = Ed25519Signer()
        s.init(true, kp.private as Ed25519PrivateKeyParameters)
        val m = ScriptVerifier.message(id, ver, code)
        s.update(m, 0, m.size)
        return hex(s.generateSignature())
    }

    @Test fun acceptsValidSignature() {
        val sig = sign("tiktok_comment_like", "1.0.0", "async function main(ctx){}")
        assertTrue(ScriptVerifier.verify(pubHex, "tiktok_comment_like", "1.0.0", "async function main(ctx){}", sig))
    }

    @Test fun rejectsTamperedCodeVersionOrId() {
        val sig = sign("tiktok_comment_like", "1.0.0", "a")
        assertFalse(ScriptVerifier.verify(pubHex, "tiktok_comment_like", "1.0.0", "b", sig))
        assertFalse(ScriptVerifier.verify(pubHex, "tiktok_comment_like", "1.0.1", "a", sig))
        assertFalse(ScriptVerifier.verify(pubHex, "tiktok_lead_engage", "1.0.0", "a", sig))
    }

    @Test fun rejectsMalformedInput() {
        assertFalse(ScriptVerifier.verify("zz", "x", "1", "a", "00"))
        assertFalse(ScriptVerifier.verify(pubHex, "x", "1", "a", ""))
    }
}
