package com.example.parkinson.server

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import org.mindrot.jbcrypt.BCrypt
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Passwords {
    const val MIN_LENGTH = 8

    fun hash(password: String): String = BCrypt.hashpw(password, BCrypt.gensalt(12))

    fun matches(password: String, hash: String): Boolean = runCatching { BCrypt.checkpw(password, hash) }.getOrDefault(false)

    /** A hash nobody can match, checked against when the mobile is unknown so both cases take as long. */
    val DUMMY_HASH: String = hash("no-such-user-" + Secrets.token())
}

object Secrets {
    private val random = SecureRandom()

    /** 256 random bits, URL-safe. */
    fun token(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun hmacSha256(key: String, text: String): String =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
            doFinal(text.toByteArray()).joinToString("") { "%02x".format(it) }
        }

    /** No 0/O or 1/I, so a code read aloud or copied by hand survives. */
    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun inviteCode(): String = String(CharArray(8) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] })

    /** Upper case, dashes, spaces and Persian digits removed or converted, as a patient may type it. */
    fun normalizeInviteCode(input: String): String = Text.asciiDigits(input).uppercase().filter { it.isLetterOrDigit() }
}

/** Staff login tokens. */
class JwtTokens(secret: String, private val lifetime: Duration = Duration.ofHours(12)) {
    private val algorithm = Algorithm.HMAC256(secret)

    val verifier: JWTVerifier = JWT.require(algorithm).withIssuer(ISSUER).build()

    fun issue(userId: Long, role: Role, now: Instant = Instant.now()): String =
        JWT.create()
            .withIssuer(ISSUER)
            .withSubject(userId.toString())
            .withClaim("role", role.name)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now + lifetime))
            .sign(algorithm)

    companion object {
        const val ISSUER = "parkinson-server"
    }
}

object Text {
    fun asciiDigits(input: String): String = buildString(input.length) {
        for (c in input) {
            append(
                when (c) {
                    in '۰'..'۹' -> '0' + (c - '۰')
                    in '٠'..'٩' -> '0' + (c - '٠')
                    else -> c
                },
            )
        }
    }

    /** 09xxxxxxxxx, from what a person may type (+98, spaces, Persian digits); null when it is not a mobile. */
    fun normalizeMobile(input: String): String? {
        val digits = asciiDigits(input).filter { it.isDigit() }
        val local = when {
            digits.startsWith("0098") -> "0" + digits.drop(4)
            digits.startsWith("98") && digits.length == 12 -> "0" + digits.drop(2)
            digits.startsWith("9") && digits.length == 10 -> "0$digits"
            else -> digits
        }
        return local.takeIf { Regex("09\\d{9}").matches(it) }
    }

    /** Same rule as the app (ProfileValidation): 10 digits, check digit, not one repeated digit. */
    fun isValidNationalId(id: String): Boolean {
        if (!Regex("\\d{10}").matches(id) || id.all { it == id[0] }) return false
        val sum = (0..8).sumOf { (id[it] - '0') * (10 - it) }
        val r = sum % 11
        val check = id[9] - '0'
        return if (r < 2) check == r else check == 11 - r
    }
}
