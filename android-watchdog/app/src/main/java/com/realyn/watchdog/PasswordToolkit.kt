package com.realyn.watchdog

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.SecureRandom
import java.util.Locale

data class PasswordPolicySpec(
    val source: String,
    val minLength: Int,
    val maxLength: Int,
    val preferredLength: Int,
    val requireLower: Boolean,
    val requireUpper: Boolean,
    val requireDigit: Boolean,
    val requireSymbol: Boolean,
    val allowLower: Boolean,
    val allowUpper: Boolean,
    val allowDigit: Boolean,
    val allowSymbol: Boolean,
    val allowedSymbols: String,
    val startWithLetter: Boolean,
    val maxConsecutiveIdentical: Int
) {
    fun summary(): String {
        val required = buildList {
            if (requireLower) add("lower")
            if (requireUpper) add("upper")
            if (requireDigit) add("digit")
            if (requireSymbol) add("symbol")
        }.joinToString(", ").ifBlank { "none" }

        return "Policy: $source | len $minLength-$maxLength (target $preferredLength) | require: $required"
    }
}

data class PasswordGenerationResult(
    val password: String,
    val policy: PasswordPolicySpec
)

object PasswordToolkit {

    private const val PROFILE_FILE_NAME = "site_profiles.json"
    private const val MIN_FLOOR_LENGTH = 8
    private const val MAX_CEILING_LENGTH = 128
    private const val DEFAULT_LENGTH = 24

    private const val LOWER_POOL = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER_POOL = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGIT_POOL = "0123456789"
    private const val DEFAULT_SYMBOL_POOL = "!@#$%^&*_-+=?"

    private val random = SecureRandom()

    private val defaultPolicy = PasswordPolicySpec(
        source = "default",
        minLength = 16,
        maxLength = 64,
        preferredLength = DEFAULT_LENGTH,
        requireLower = true,
        requireUpper = true,
        requireDigit = true,
        requireSymbol = true,
        allowLower = true,
        allowUpper = true,
        allowDigit = true,
        allowSymbol = true,
        allowedSymbols = DEFAULT_SYMBOL_POOL,
        startWithLetter = true,
        maxConsecutiveIdentical = 2
    )

    private val categoryPolicies = mapOf(
        "email" to defaultPolicy.copy(source = "category:email", preferredLength = 22),
        "banking" to defaultPolicy.copy(
            source = "category:banking",
            minLength = 14,
            maxLength = 32,
            preferredLength = 18,
            requireSymbol = false,
            allowSymbol = false
        ),
        "social" to defaultPolicy.copy(source = "category:social", preferredLength = 20),
        "developer" to defaultPolicy.copy(
            source = "category:developer",
            minLength = 18,
            maxLength = 128,
            preferredLength = 28
        ),
        "other" to defaultPolicy.copy(source = "category:other", preferredLength = 22)
    )

    fun generateStrongPassword(length: Int = DEFAULT_LENGTH): String {
        val policy = normalizePolicy(defaultPolicy.copy(preferredLength = length))
        return generateFromPolicy(policy)
    }

    fun resolvePolicyPreview(
        context: Context,
        service: String,
        url: String,
        category: String
    ): PasswordPolicySpec {
        return resolvePolicy(context, service, url, category)
    }

    fun generateAdaptivePassword(
        context: Context,
        service: String,
        url: String,
        category: String
    ): PasswordGenerationResult {
        val policy = resolvePolicy(context, service, url, category)
        val password = generateFromPolicy(policy)
        return PasswordGenerationResult(password = password, policy = policy)
    }

    private fun resolvePolicy(
        context: Context,
        service: String,
        url: String,
        category: String
    ): PasswordPolicySpec {
        val policies = loadSitePolicies(context)
        val domain = extractDomain(url)

        if (domain.isNotBlank()) {
            val matchedByDomain = matchByDomain(domain, policies)
            if (matchedByDomain != null) {
                return matchedByDomain.second.copy(source = "site:${matchedByDomain.first}")
            }
        }

        val matchedByService = matchByService(service, policies)
        if (matchedByService != null) {
            return matchedByService.second.copy(source = "site:${matchedByService.first}")
        }

        val normalizedCategory = category.trim().lowercase(Locale.US).ifBlank { "other" }
        val fallback = categoryPolicies[normalizedCategory] ?: defaultPolicy
        return normalizePolicy(fallback.copy(source = "category:$normalizedCategory"))
    }

    private fun loadSitePolicies(context: Context): Map<String, PasswordPolicySpec> {
        val payload = readProfilePayload(context) ?: return emptyMap()
        val profiles = payload.optJSONObject("profiles") ?: return emptyMap()
        val result = linkedMapOf<String, PasswordPolicySpec>()
        val iterator = profiles.keys()
        while (iterator.hasNext()) {
            val domain = iterator.next().trim().lowercase(Locale.US)
            if (domain.isBlank()) {
                continue
            }
            val item = profiles.optJSONObject(domain) ?: continue
            val policy = item.optJSONObject("password_policy") ?: continue
            result[domain] = parseSitePolicy(policy, domain)
        }
        return result
    }

    private fun parseSitePolicy(policy: JSONObject, domain: String): PasswordPolicySpec {
        val allowedRaw = sanitizeSymbols(policy.optString("allowed_symbols", DEFAULT_SYMBOL_POOL))
        val disallowedSet = sanitizeSymbols(policy.optString("disallowed_symbols", ""))
            .toSet()
        val effectiveSymbols = allowedRaw.filterNot { disallowedSet.contains(it) }
            .ifBlank { DEFAULT_SYMBOL_POOL }

        val parsed = PasswordPolicySpec(
            source = "site:$domain",
            minLength = policy.optInt("min_length", defaultPolicy.minLength),
            maxLength = policy.optInt("max_length", defaultPolicy.maxLength),
            preferredLength = policy.optInt("preferred_length", defaultPolicy.preferredLength),
            requireLower = policy.optBoolean("require_lower", defaultPolicy.requireLower),
            requireUpper = policy.optBoolean("require_upper", defaultPolicy.requireUpper),
            requireDigit = policy.optBoolean("require_digit", defaultPolicy.requireDigit),
            requireSymbol = policy.optBoolean("require_symbol", defaultPolicy.requireSymbol),
            allowLower = policy.optBoolean("allow_lower", true),
            allowUpper = policy.optBoolean("allow_upper", true),
            allowDigit = policy.optBoolean("allow_digit", true),
            allowSymbol = policy.optBoolean("allow_symbol", true),
            allowedSymbols = effectiveSymbols,
            startWithLetter = policy.optBoolean("start_with_letter", defaultPolicy.startWithLetter),
            maxConsecutiveIdentical = policy.optInt("max_consecutive_identical", defaultPolicy.maxConsecutiveIdentical)
        )
        return normalizePolicy(parsed)
    }

    private fun readProfilePayload(context: Context): JSONObject? {
        val localOverride = File(context.filesDir, PROFILE_FILE_NAME)
        val content = when {
            localOverride.exists() -> runCatching { localOverride.readText() }.getOrNull()
            else -> runCatching {
                context.assets.open(PROFILE_FILE_NAME).bufferedReader().use { it.readText() }
            }.getOrNull()
        } ?: return null

        return runCatching { JSONObject(content) }.getOrNull()
    }

    private fun extractDomain(url: String): String {
        val normalized = CredentialPolicy.normalizeUrl(url)
        if (normalized.isBlank()) {
            return ""
        }
        return runCatching {
            URI(normalized).host.orEmpty().lowercase(Locale.US).removePrefix("www.")
        }.getOrDefault("")
    }

    private fun matchByDomain(
        domain: String,
        policies: Map<String, PasswordPolicySpec>
    ): Pair<String, PasswordPolicySpec>? {
        return policies.entries
            .filter { domain == it.key || domain.endsWith(".${it.key}") }
            .maxByOrNull { it.key.length }
            ?.toPair()
    }

    private fun matchByService(
        service: String,
        policies: Map<String, PasswordPolicySpec>
    ): Pair<String, PasswordPolicySpec>? {
        val label = service.trim().lowercase(Locale.US)
        if (label.isBlank()) {
            return null
        }

        return policies.entries
            .mapNotNull { entry ->
                val token = entry.key.substringBefore(".")
                if (token.length < 3) {
                    return@mapNotNull null
                }
                if (label.contains(token)) {
                    entry
                } else {
                    null
                }
            }
            .maxByOrNull { it.key.length }
            ?.toPair()
    }

    private fun normalizePolicy(input: PasswordPolicySpec): PasswordPolicySpec {
        val min = input.minLength.coerceIn(MIN_FLOOR_LENGTH, MAX_CEILING_LENGTH)
        val max = input.maxLength.coerceIn(min, MAX_CEILING_LENGTH)
        val preferred = input.preferredLength.coerceIn(min, max)

        var allowLower = input.allowLower || input.requireLower
        var allowUpper = input.allowUpper || input.requireUpper
        var allowDigit = input.allowDigit || input.requireDigit
        var allowSymbol = input.allowSymbol || input.requireSymbol

        var symbols = sanitizeSymbols(input.allowedSymbols)
        if (allowSymbol && symbols.isBlank()) {
            symbols = DEFAULT_SYMBOL_POOL
        }
        if (symbols.isBlank()) {
            allowSymbol = false
        }

        if (!allowLower && !allowUpper && !allowDigit && !allowSymbol) {
            allowLower = true
            allowUpper = true
            allowDigit = true
        }

        val canStartWithLetter = allowLower || allowUpper

        return input.copy(
            minLength = min,
            maxLength = max,
            preferredLength = preferred,
            allowLower = allowLower,
            allowUpper = allowUpper,
            allowDigit = allowDigit,
            allowSymbol = allowSymbol,
            allowedSymbols = symbols,
            startWithLetter = input.startWithLetter && canStartWithLetter,
            maxConsecutiveIdentical = input.maxConsecutiveIdentical.coerceIn(1, 4)
        )
    }

    private fun sanitizeSymbols(value: String): String {
        val seen = linkedSetOf<Char>()
        value.forEach { ch ->
            if (!ch.isWhitespace() && !ch.isLetterOrDigit()) {
                seen += ch
            }
        }
        return seen.joinToString("")
    }

    private fun generateFromPolicy(policy: PasswordPolicySpec): String {
        repeat(96) {
            val candidate = buildCandidate(policy)
            if (isCompliant(candidate, policy)) {
                return candidate
            }
        }
        return generateLegacy(policy.preferredLength.coerceAtLeast(DEFAULT_LENGTH))
    }

    private fun buildCandidate(policy: PasswordPolicySpec): String {
        val lowerPool = if (policy.allowLower) LOWER_POOL else ""
        val upperPool = if (policy.allowUpper) UPPER_POOL else ""
        val digitPool = if (policy.allowDigit) DIGIT_POOL else ""
        val symbolPool = if (policy.allowSymbol) policy.allowedSymbols else ""
        val allPool = lowerPool + upperPool + digitPool + symbolPool

        if (allPool.isBlank()) {
            return ""
        }

        val requiredChars = mutableListOf<Char>()
        if (policy.requireLower && lowerPool.isNotBlank()) requiredChars += lowerPool.randomChar()
        if (policy.requireUpper && upperPool.isNotBlank()) requiredChars += upperPool.randomChar()
        if (policy.requireDigit && digitPool.isNotBlank()) requiredChars += digitPool.randomChar()
        if (policy.requireSymbol && symbolPool.isNotBlank()) requiredChars += symbolPool.randomChar()

        val targetLength = policy.preferredLength
            .coerceAtLeast(requiredChars.size)
            .coerceIn(policy.minLength, policy.maxLength)

        val chars = requiredChars.toMutableList()
        while (chars.size < targetLength) {
            chars += allPool.randomChar()
        }
        shuffle(chars)

        if (policy.startWithLetter && chars.isNotEmpty() && !chars.first().isLetter()) {
            val index = chars.indexOfFirst { it.isLetter() }
            if (index > 0) {
                val first = chars[0]
                chars[0] = chars[index]
                chars[index] = first
            }
        }

        return chars.joinToString("")
    }

    private fun isCompliant(password: String, policy: PasswordPolicySpec): Boolean {
        if (password.length !in policy.minLength..policy.maxLength) {
            return false
        }
        if (policy.requireLower && !password.any { it.isLowerCase() }) {
            return false
        }
        if (policy.requireUpper && !password.any { it.isUpperCase() }) {
            return false
        }
        if (policy.requireDigit && !password.any { it.isDigit() }) {
            return false
        }
        if (policy.requireSymbol && !password.any { !it.isLetterOrDigit() }) {
            return false
        }
        if (!policy.allowLower && password.any { it.isLowerCase() }) {
            return false
        }
        if (!policy.allowUpper && password.any { it.isUpperCase() }) {
            return false
        }
        if (!policy.allowDigit && password.any { it.isDigit() }) {
            return false
        }
        if (!policy.allowSymbol && password.any { !it.isLetterOrDigit() }) {
            return false
        }
        if (policy.allowSymbol && password.any { !it.isLetterOrDigit() && !policy.allowedSymbols.contains(it) }) {
            return false
        }
        if (policy.startWithLetter && password.firstOrNull()?.isLetter() != true) {
            return false
        }

        var runLength = 1
        for (index in 1 until password.length) {
            if (password[index] == password[index - 1]) {
                runLength += 1
                if (runLength > policy.maxConsecutiveIdentical) {
                    return false
                }
            } else {
                runLength = 1
            }
        }

        return true
    }

    private fun shuffle(items: MutableList<Char>) {
        for (index in items.lastIndex downTo 1) {
            val swap = random.nextInt(index + 1)
            if (swap == index) {
                continue
            }
            val current = items[index]
            items[index] = items[swap]
            items[swap] = current
        }
    }

    private fun String.randomChar(): Char = this[random.nextInt(this.length)]

    private fun generateLegacy(length: Int): String {
        val target = length.coerceIn(MIN_FLOOR_LENGTH, MAX_CEILING_LENGTH)
        val pool = LOWER_POOL + UPPER_POOL + DIGIT_POOL + DEFAULT_SYMBOL_POOL
        val chars = CharArray(target) {
            pool[random.nextInt(pool.length)]
        }
        return String(chars)
    }
}
