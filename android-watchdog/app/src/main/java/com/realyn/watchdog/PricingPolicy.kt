package com.realyn.watchdog

import android.content.Context
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

data class CompetitorPricePoint(
    val name: String,
    val monthlyUsd: Double,
    val sourceUrl: String,
    val observedAt: String
)

data class RegionalPricingRule(
    val regionCode: String,
    val regionLabel: String,
    val currencyCode: String,
    val usdToLocalRate: Double,
    val affordabilityFactor: Double,
    val countries: Set<String> = emptySet()
) {
    fun effectiveMultiplier(): Double = usdToLocalRate * affordabilityFactor
}

data class FeatureAccessTier(
    val credentialRecordsLimit: Int,
    val queueActionsLimit: Int,
    val breachScansPerDay: Int,
    val continuousScanEnabled: Boolean,
    val overlayAssistantEnabled: Boolean,
    val rotationQueueEnabled: Boolean,
    val aiHotlineEnabled: Boolean
)

data class FamilyAgeBand(
    val code: String,
    val label: String,
    val minAge: Int,
    val maxAge: Int,
    val allowStoredCredentialCopy: Boolean,
    val allowOverlayAssistant: Boolean,
    val requireGuardianApprovalForSensitiveActions: Boolean
)

data class FamilyAgeProtocolPolicy(
    val enabled: Boolean,
    val graduationAge: Int,
    val parentControlsPlanOptOut: Boolean,
    val bands: List<FamilyAgeBand>
)

data class PricingPolicyModel(
    val baseCurrencyCode: String,
    val freeTrialDays: Int,
    val targetDiscountPercent: Double,
    val competitorAverageMonthlyUsd: Double,
    val referralOfferEnabled: Boolean,
    val referralDiscountPercent: Double,
    val referralRequiresRecommendation: Boolean,
    val weeklyUsd: Double,
    val monthlyUsd: Double,
    val familyMonthlyUsd: Double,
    val yearlyUsd: Double,
    val yearlyMonthsCharged: Int,
    val competitorReferences: List<CompetitorPricePoint>,
    val regionalRules: List<RegionalPricingRule>,
    val freeTierAccess: FeatureAccessTier,
    val paidTierAccess: FeatureAccessTier,
    val familyParentTierAccess: FeatureAccessTier,
    val familyChildTierAccess: FeatureAccessTier,
    val familyAgePolicy: FamilyAgeProtocolPolicy
)

data class ResolvedRegionalPricing(
    val regionCode: String,
    val regionLabel: String,
    val currencyCode: String,
    val multiplier: Double,
    val weekly: Double,
    val monthly: Double,
    val yearly: Double,
    val yearlyMonthsCharged: Int
)

data class TrialStatus(
    val startedAtEpochMs: Long,
    val daysElapsed: Int,
    val daysRemaining: Int,
    val inTrial: Boolean
)

data class NextPaymentDueStatus(
    val statusCode: String,
    val planId: String,
    val dueAtEpochMs: Long
)

data class FeedbackStatus(
    val performanceRating: Int,
    val recommendToFriends: Boolean,
    val updatedAtEpochMs: Long
) {
    val completed: Boolean
        get() = performanceRating in 1..5
}

data class EntitlementStatus(
    val isLifetimePro: Boolean,
    val source: String
)

data class ResolvedFeatureAccess(
    val tierCode: String,
    val paidAccess: Boolean,
    val features: FeatureAccessTier
)

data class ProfileControlPolicy(
    val roleCode: String,
    val ageYears: Int,
    val ageBandCode: String,
    val ageBandLabel: String,
    val familyPlanUmbrella: Boolean,
    val parentControlsPlanOptOut: Boolean,
    val graduatedToFamilySingle: Boolean,
    val canViewGuardianFeed: Boolean,
    val canManagePlan: Boolean,
    val canCopyStoredCredentials: Boolean,
    val canUseOverlayAssistant: Boolean,
    val requiresGuardianApprovalForSensitiveActions: Boolean
)

private data class FamilyRoleContext(
    val roleCode: String,
    val ageYears: Int,
    val ageBand: FamilyAgeBand?,
    val familyPlanUmbrella: Boolean,
    val graduatedToFamilySingle: Boolean
)

data class DailyQuotaStatus(
    val limitPerDay: Int,
    val unlimited: Boolean,
    val usedToday: Int,
    val remainingToday: Int
) {
    val allowed: Boolean
        get() = unlimited || remainingToday > 0
}

object PricingPolicy {

    private const val SETTINGS_FILE_NAME = "workspace_settings.json"
    private const val KEY_TRIAL_STARTED_AT = "pricing_trial_started_at_epoch_ms"
    private const val KEY_SELECTED_PLAN = "pricing_selected_plan"
    private const val KEY_SELECTED_PLAN_AT = "pricing_selected_plan_at_epoch_ms"
    private const val KEY_LIFETIME_PRO = "pricing_lifetime_pro_enabled"
    private const val KEY_LIFETIME_PRO_SOURCE = "pricing_lifetime_pro_source"
    private const val KEY_FEEDBACK_PERFORMANCE_RATING = "pricing_feedback_performance_rating"
    private const val KEY_FEEDBACK_RECOMMEND_FRIENDS = "pricing_feedback_recommend_friends"
    private const val KEY_FEEDBACK_UPDATED_AT = "pricing_feedback_updated_at_epoch_ms"
    private const val KEY_BREACH_SCAN_USAGE_DAY_UTC = "pricing_breach_scan_usage_day_utc"
    private const val KEY_BREACH_SCAN_USAGE_COUNT = "pricing_breach_scan_usage_count"
    private const val LIFETIME_SOURCE_NONE = "none"
    private const val LIFETIME_SOURCE_MANUAL = "manual"
    private const val LIFETIME_SOURCE_ALLOWLIST = "device_allowlist"
    private const val ROLE_PARENT = "parent"
    private const val ROLE_CHILD = "child"
    private const val ROLE_FAMILY_SINGLE = "family_single"
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    private val defaultCompetitors = listOf(
        CompetitorPricePoint(
            name = "Bitwarden",
            monthlyUsd = 1.65,
            sourceUrl = "https://bitwarden.com/pricing/",
            observedAt = "2026-02-21"
        ),
        CompetitorPricePoint(
            name = "1Password",
            monthlyUsd = 2.99,
            sourceUrl = "https://start.1password.com/sign-up/family?currency=USD",
            observedAt = "2026-02-21"
        ),
        CompetitorPricePoint(
            name = "Dashlane",
            monthlyUsd = 4.99,
            sourceUrl = "https://support.dashlane.com/hc/en-us/articles/25851560554258-How-a-plan-change-affects-your-invoice",
            observedAt = "2026-02-21"
        )
    )

    private val defaultRegionalRules = listOf(
        RegionalPricingRule(
            regionCode = "US",
            regionLabel = "United States",
            currencyCode = "USD",
            usdToLocalRate = 1.0,
            affordabilityFactor = 1.0
        ),
        RegionalPricingRule(
            regionCode = "GB",
            regionLabel = "United Kingdom",
            currencyCode = "GBP",
            usdToLocalRate = 0.79,
            affordabilityFactor = 1.20
        ),
        RegionalPricingRule(
            regionCode = "TH",
            regionLabel = "Thailand",
            currencyCode = "THB",
            usdToLocalRate = 35.8,
            affordabilityFactor = 0.55
        ),
        RegionalPricingRule(
            regionCode = "PH",
            regionLabel = "Philippines",
            currencyCode = "PHP",
            usdToLocalRate = 56.0,
            affordabilityFactor = 0.60
        ),
        RegionalPricingRule(
            regionCode = "EU",
            regionLabel = "European Union",
            currencyCode = "EUR",
            usdToLocalRate = 0.92,
            affordabilityFactor = 1.05,
            countries = setOf(
                "AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GR",
                "HR", "HU", "IE", "IT", "LT", "LU", "LV", "MT", "NL", "PL", "PT", "RO",
                "SE", "SI", "SK"
            )
        ),
        RegionalPricingRule(
            regionCode = "AU",
            regionLabel = "Australia",
            currencyCode = "AUD",
            usdToLocalRate = 1.53,
            affordabilityFactor = 1.05
        ),
        RegionalPricingRule(
            regionCode = "CA",
            regionLabel = "Canada",
            currencyCode = "CAD",
            usdToLocalRate = 1.36,
            affordabilityFactor = 1.03
        ),
        RegionalPricingRule(
            regionCode = "SG",
            regionLabel = "Singapore",
            currencyCode = "SGD",
            usdToLocalRate = 1.35,
            affordabilityFactor = 1.02
        ),
        RegionalPricingRule(
            regionCode = "JP",
            regionLabel = "Japan",
            currencyCode = "JPY",
            usdToLocalRate = 150.0,
            affordabilityFactor = 0.95
        )
    )

    private val defaultFreeTierAccess = FeatureAccessTier(
        credentialRecordsLimit = 40,
        queueActionsLimit = 5,
        breachScansPerDay = 2,
        continuousScanEnabled = false,
        overlayAssistantEnabled = false,
        rotationQueueEnabled = true,
        aiHotlineEnabled = false
    )

    private val defaultPaidTierAccess = FeatureAccessTier(
        credentialRecordsLimit = -1,
        queueActionsLimit = -1,
        breachScansPerDay = -1,
        continuousScanEnabled = true,
        overlayAssistantEnabled = true,
        rotationQueueEnabled = true,
        aiHotlineEnabled = true
    )

    private val defaultFamilyParentTierAccess = FeatureAccessTier(
        credentialRecordsLimit = -1,
        queueActionsLimit = -1,
        breachScansPerDay = -1,
        continuousScanEnabled = true,
        overlayAssistantEnabled = true,
        rotationQueueEnabled = true,
        aiHotlineEnabled = true
    )

    private val defaultFamilyChildTierAccess = FeatureAccessTier(
        credentialRecordsLimit = 80,
        queueActionsLimit = 8,
        breachScansPerDay = 5,
        continuousScanEnabled = false,
        overlayAssistantEnabled = false,
        rotationQueueEnabled = true,
        aiHotlineEnabled = false
    )

    private val defaultFamilyAgePolicy = FamilyAgeProtocolPolicy(
        enabled = true,
        graduationAge = 18,
        parentControlsPlanOptOut = true,
        bands = listOf(
            FamilyAgeBand(
                code = "foundation_0_12",
                label = "Foundation 0-12",
                minAge = 0,
                maxAge = 12,
                allowStoredCredentialCopy = false,
                allowOverlayAssistant = false,
                requireGuardianApprovalForSensitiveActions = true
            ),
            FamilyAgeBand(
                code = "guided_13_15",
                label = "Guided 13-15",
                minAge = 13,
                maxAge = 15,
                allowStoredCredentialCopy = false,
                allowOverlayAssistant = false,
                requireGuardianApprovalForSensitiveActions = true
            ),
            FamilyAgeBand(
                code = "transition_16_17",
                label = "Transition 16-17",
                minAge = 16,
                maxAge = 17,
                allowStoredCredentialCopy = true,
                allowOverlayAssistant = true,
                requireGuardianApprovalForSensitiveActions = true
            ),
            FamilyAgeBand(
                code = "family_single_18_plus",
                label = "Family single 18+",
                minAge = 18,
                maxAge = 130,
                allowStoredCredentialCopy = true,
                allowOverlayAssistant = true,
                requireGuardianApprovalForSensitiveActions = false
            )
        )
    )

    private val defaultModel = PricingPolicyModel(
        baseCurrencyCode = "USD",
        freeTrialDays = 7,
        targetDiscountPercent = 15.0,
        competitorAverageMonthlyUsd = 3.21,
        referralOfferEnabled = true,
        referralDiscountPercent = 10.0,
        referralRequiresRecommendation = true,
        weeklyUsd = 0.63,
        monthlyUsd = 2.73,
        familyMonthlyUsd = 4.39,
        yearlyUsd = 27.30,
        yearlyMonthsCharged = 10,
        competitorReferences = defaultCompetitors,
        regionalRules = defaultRegionalRules,
        freeTierAccess = defaultFreeTierAccess,
        paidTierAccess = defaultPaidTierAccess,
        familyParentTierAccess = defaultFamilyParentTierAccess,
        familyChildTierAccess = defaultFamilyChildTierAccess,
        familyAgePolicy = defaultFamilyAgePolicy
    )

    fun load(context: Context): PricingPolicyModel {
        val payload = readSettingsPayload(context) ?: return defaultModel
        val pricing = payload.optJSONObject("pricing") ?: return defaultModel

        val plans = pricing.optJSONObject("plans") ?: JSONObject()
        val referralOffer = pricing.optJSONObject("referral_offer") ?: JSONObject()
        val featureAccess = pricing.optJSONObject("feature_access") ?: JSONObject()
        val familyAgeProtocols = pricing.optJSONObject("family_age_protocols")
        val competitors = parseCompetitors(pricing.optJSONArray("competitor_reference"))
        val regionalRules = parseRegionalRules(pricing.optJSONArray("regional_pricing"))
        val freeTier = parseFeatureAccessTier(
            featureAccess.optJSONObject("free"),
            defaultFreeTierAccess
        )
        val paidTier = parseFeatureAccessTier(
            featureAccess.optJSONObject("paid"),
            defaultPaidTierAccess
        )
        val familyParentTier = parseFeatureAccessTier(
            featureAccess.optJSONObject("family_parent"),
            defaultFamilyParentTierAccess
        )
        val familyChildTier = parseFeatureAccessTier(
            featureAccess.optJSONObject("family_child"),
            defaultFamilyChildTierAccess
        )
        val familyAgePolicy = parseFamilyAgeProtocolPolicy(familyAgeProtocols)

        return PricingPolicyModel(
            baseCurrencyCode = pricing.optString("currency", defaultModel.baseCurrencyCode),
            freeTrialDays = pricing.optInt("free_trial_days", defaultModel.freeTrialDays).coerceAtLeast(1),
            targetDiscountPercent = pricing.optDouble(
                "target_discount_vs_competitor_avg_percent",
                defaultModel.targetDiscountPercent
            ),
            competitorAverageMonthlyUsd = pricing.optDouble(
                "competitor_average_monthly_usd",
                defaultModel.competitorAverageMonthlyUsd
            ),
            referralOfferEnabled = referralOffer.optBoolean("enabled", defaultModel.referralOfferEnabled),
            referralDiscountPercent = referralOffer.optDouble(
                "recommend_discount_percent",
                defaultModel.referralDiscountPercent
            ).coerceIn(0.0, 80.0),
            referralRequiresRecommendation = referralOffer.optBoolean(
                "requires_recommendation",
                defaultModel.referralRequiresRecommendation
            ),
            weeklyUsd = plans.optDouble("weekly_usd", defaultModel.weeklyUsd),
            monthlyUsd = plans.optDouble("monthly_usd", defaultModel.monthlyUsd),
            familyMonthlyUsd = plans.optDouble("family_monthly_usd", defaultModel.familyMonthlyUsd),
            yearlyUsd = plans.optDouble("yearly_usd", defaultModel.yearlyUsd),
            yearlyMonthsCharged = plans.optInt("yearly_months_charged", defaultModel.yearlyMonthsCharged),
            competitorReferences = if (competitors.isEmpty()) defaultCompetitors else competitors,
            regionalRules = if (regionalRules.isEmpty()) defaultRegionalRules else regionalRules,
            freeTierAccess = freeTier,
            paidTierAccess = paidTier,
            familyParentTierAccess = familyParentTier,
            familyChildTierAccess = familyChildTier,
            familyAgePolicy = familyAgePolicy
        )
    }

    fun resolveForCurrentRegion(context: Context, model: PricingPolicyModel = load(context)): ResolvedRegionalPricing {
        val countryCode = Locale.getDefault().country.ifBlank { "US" }.uppercase(Locale.US)
        val matched = model.regionalRules.firstOrNull { it.countries.contains(countryCode) }
            ?: model.regionalRules.firstOrNull { it.regionCode.equals(countryCode, ignoreCase = true) }
            ?: model.regionalRules.firstOrNull { it.regionCode.equals("US", ignoreCase = true) }
            ?: defaultRegionalRules.first()

        val multiplier = matched.effectiveMultiplier()
        val monthly = roundForCurrency(matched.currencyCode, model.monthlyUsd * multiplier)
        val weekly = roundForCurrency(matched.currencyCode, monthly * 12.0 / 52.0)
        val yearly = roundForCurrency(
            matched.currencyCode,
            monthly * model.yearlyMonthsCharged
        )

        return ResolvedRegionalPricing(
            regionCode = matched.regionCode,
            regionLabel = matched.regionLabel,
            currencyCode = matched.currencyCode,
            multiplier = multiplier,
            weekly = weekly,
            monthly = monthly,
            yearly = yearly,
            yearlyMonthsCharged = model.yearlyMonthsCharged
        )
    }

    fun resolveFamilyMonthlyPrice(
        context: Context,
        model: PricingPolicyModel = load(context),
        regional: ResolvedRegionalPricing = resolveForCurrentRegion(context, model)
    ): Double {
        return roundForCurrency(
            regional.currencyCode,
            model.familyMonthlyUsd * regional.multiplier
        )
    }

    fun ensureTrial(context: Context): TrialStatus {
        val model = load(context)
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val stored = prefs.getLong(KEY_TRIAL_STARTED_AT, 0L)
        val startedAt = if (stored <= 0L || stored > now) {
            prefs.edit().putLong(KEY_TRIAL_STARTED_AT, now).apply()
            now
        } else {
            stored
        }
        return computeTrialStatus(startedAt, now, model.freeTrialDays)
    }

    fun entitlement(context: Context): EntitlementStatus {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val persistedLifetime = prefs.getBoolean(KEY_LIFETIME_PRO, false)
        if (persistedLifetime) {
            return EntitlementStatus(
                isLifetimePro = true,
                source = prefs.getString(KEY_LIFETIME_PRO_SOURCE, LIFETIME_SOURCE_MANUAL)
                    ?: LIFETIME_SOURCE_MANUAL
            )
        }

        if (isDeviceAllowlistedForLifetime(context)) {
            prefs.edit()
                .putBoolean(KEY_LIFETIME_PRO, true)
                .putString(KEY_LIFETIME_PRO_SOURCE, LIFETIME_SOURCE_ALLOWLIST)
                .apply()
            return EntitlementStatus(
                isLifetimePro = true,
                source = LIFETIME_SOURCE_ALLOWLIST
            )
        }

        return EntitlementStatus(
            isLifetimePro = false,
            source = LIFETIME_SOURCE_NONE
        )
    }

    fun grantLifetimePro(context: Context, source: String = LIFETIME_SOURCE_MANUAL) {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val normalized = when (source.trim().lowercase(Locale.US)) {
            LIFETIME_SOURCE_ALLOWLIST -> LIFETIME_SOURCE_ALLOWLIST
            else -> LIFETIME_SOURCE_MANUAL
        }
        prefs.edit()
            .putBoolean(KEY_LIFETIME_PRO, true)
            .putString(KEY_LIFETIME_PRO_SOURCE, normalized)
            .apply()
    }

    fun feedbackStatus(context: Context): FeedbackStatus {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        return FeedbackStatus(
            performanceRating = prefs.getInt(KEY_FEEDBACK_PERFORMANCE_RATING, 0).coerceIn(0, 5),
            recommendToFriends = prefs.getBoolean(KEY_FEEDBACK_RECOMMEND_FRIENDS, false),
            updatedAtEpochMs = prefs.getLong(KEY_FEEDBACK_UPDATED_AT, 0L)
        )
    }

    fun saveFeedback(context: Context, performanceRating: Int, recommendToFriends: Boolean) {
        val normalizedRating = performanceRating.coerceIn(1, 5)
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_FEEDBACK_PERFORMANCE_RATING, normalizedRating)
            .putBoolean(KEY_FEEDBACK_RECOMMEND_FRIENDS, recommendToFriends)
            .putLong(KEY_FEEDBACK_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun resolveReferralDiscountPercent(
        model: PricingPolicyModel,
        feedbackStatus: FeedbackStatus
    ): Double {
        if (!model.referralOfferEnabled) {
            return 0.0
        }
        if (model.referralRequiresRecommendation && !feedbackStatus.recommendToFriends) {
            return 0.0
        }
        return model.referralDiscountPercent.coerceIn(0.0, 80.0)
    }

    fun applyReferralDiscount(regional: ResolvedRegionalPricing, discountPercent: Double): ResolvedRegionalPricing {
        val normalizedDiscount = discountPercent.coerceIn(0.0, 80.0)
        if (normalizedDiscount <= 0.0) {
            return regional
        }
        val multiplier = (100.0 - normalizedDiscount) / 100.0
        return regional.copy(
            weekly = roundForCurrency(regional.currencyCode, regional.weekly * multiplier),
            monthly = roundForCurrency(regional.currencyCode, regional.monthly * multiplier),
            yearly = roundForCurrency(regional.currencyCode, regional.yearly * multiplier)
        )
    }

    fun resolveFeatureAccess(
        context: Context,
        model: PricingPolicyModel = load(context)
    ): ResolvedFeatureAccess {
        val entitlement = entitlement(context)
        if (entitlement.isLifetimePro) {
            return ResolvedFeatureAccess(
                tierCode = "lifetime",
                paidAccess = true,
                features = model.paidTierAccess
            )
        }

        val trial = ensureTrial(context)
        if (trial.inTrial) {
            return ResolvedFeatureAccess(
                tierCode = "trial",
                paidAccess = true,
                features = model.paidTierAccess
            )
        }

        val selectedPlan = rawSelectedPlan(context).trim().lowercase(Locale.US)
        if (selectedPlan == "family") {
            val familyRole = resolveFamilyRoleContext(context, model)
            return when (familyRole.roleCode) {
                ROLE_CHILD -> ResolvedFeatureAccess(
                    tierCode = "family_child",
                    paidAccess = true,
                    features = model.familyChildTierAccess
                )
                ROLE_FAMILY_SINGLE -> ResolvedFeatureAccess(
                    tierCode = "family_single",
                    paidAccess = true,
                    features = model.familyParentTierAccess
                )
                else -> ResolvedFeatureAccess(
                    tierCode = "family_parent",
                    paidAccess = true,
                    features = model.familyParentTierAccess
                )
            }
        }

        if (hasPaidPlanSelected(context)) {
            return ResolvedFeatureAccess(
                tierCode = "paid",
                paidAccess = true,
                features = model.paidTierAccess
            )
        }

        return ResolvedFeatureAccess(
            tierCode = "free",
            paidAccess = false,
            features = model.freeTierAccess
        )
    }

    fun resolveProfileControl(
        context: Context,
        access: ResolvedFeatureAccess = resolveFeatureAccess(context),
        model: PricingPolicyModel = load(context)
    ): ProfileControlPolicy {
        val familyRole = resolveFamilyRoleContext(context, model)
        if (familyRole.roleCode == ROLE_CHILD) {
            val band = familyRole.ageBand
            return ProfileControlPolicy(
                roleCode = ROLE_CHILD,
                ageYears = familyRole.ageYears,
                ageBandCode = band?.code ?: "unknown",
                ageBandLabel = band?.label ?: "Child",
                familyPlanUmbrella = familyRole.familyPlanUmbrella,
                parentControlsPlanOptOut = model.familyAgePolicy.parentControlsPlanOptOut,
                graduatedToFamilySingle = false,
                canViewGuardianFeed = false,
                canManagePlan = false,
                canCopyStoredCredentials = band?.allowStoredCredentialCopy == true,
                canUseOverlayAssistant = band?.allowOverlayAssistant == true && access.features.overlayAssistantEnabled,
                requiresGuardianApprovalForSensitiveActions = band?.requireGuardianApprovalForSensitiveActions != false
            )
        }

        if (familyRole.roleCode == ROLE_FAMILY_SINGLE) {
            val band = familyRole.ageBand
            return ProfileControlPolicy(
                roleCode = ROLE_FAMILY_SINGLE,
                ageYears = familyRole.ageYears,
                ageBandCode = band?.code ?: "family_single",
                ageBandLabel = band?.label ?: "Family single 18+",
                familyPlanUmbrella = familyRole.familyPlanUmbrella,
                parentControlsPlanOptOut = model.familyAgePolicy.parentControlsPlanOptOut,
                graduatedToFamilySingle = familyRole.graduatedToFamilySingle,
                canViewGuardianFeed = false,
                canManagePlan = false,
                canCopyStoredCredentials = true,
                canUseOverlayAssistant = access.features.overlayAssistantEnabled,
                requiresGuardianApprovalForSensitiveActions = false
            )
        }

        return ProfileControlPolicy(
            roleCode = ROLE_PARENT,
            ageYears = -1,
            ageBandCode = "adult_parent",
            ageBandLabel = "Parent",
            familyPlanUmbrella = familyRole.familyPlanUmbrella,
            parentControlsPlanOptOut = model.familyAgePolicy.parentControlsPlanOptOut,
            graduatedToFamilySingle = false,
            canViewGuardianFeed = true,
            canManagePlan = true,
            canCopyStoredCredentials = true,
            canUseOverlayAssistant = access.features.overlayAssistantEnabled,
            requiresGuardianApprovalForSensitiveActions = false
        )
    }

    fun breachScanQuota(
        context: Context,
        model: PricingPolicyModel = load(context)
    ): DailyQuotaStatus {
        val access = resolveFeatureAccess(context, model)
        val limit = access.features.breachScansPerDay
        if (limit < 0) {
            return DailyQuotaStatus(
                limitPerDay = limit,
                unlimited = true,
                usedToday = 0,
                remainingToday = Int.MAX_VALUE
            )
        }
        if (limit == 0) {
            return DailyQuotaStatus(
                limitPerDay = 0,
                unlimited = false,
                usedToday = 0,
                remainingToday = 0
            )
        }

        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val dayKey = utcDayKey(System.currentTimeMillis())
        val storedDay = prefs.getString(KEY_BREACH_SCAN_USAGE_DAY_UTC, "") ?: ""
        val usedToday = if (storedDay == dayKey) {
            prefs.getInt(KEY_BREACH_SCAN_USAGE_COUNT, 0).coerceAtLeast(0)
        } else {
            0
        }
        val remaining = max(0, limit - usedToday)

        return DailyQuotaStatus(
            limitPerDay = limit,
            unlimited = false,
            usedToday = usedToday,
            remainingToday = remaining
        )
    }

    fun recordBreachScanUsage(
        context: Context,
        model: PricingPolicyModel = load(context)
    ) {
        val access = resolveFeatureAccess(context, model)
        val limit = access.features.breachScansPerDay
        if (limit <= 0) {
            return
        }

        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val dayKey = utcDayKey(System.currentTimeMillis())
        val storedDay = prefs.getString(KEY_BREACH_SCAN_USAGE_DAY_UTC, "") ?: ""
        val currentCount = if (storedDay == dayKey) {
            prefs.getInt(KEY_BREACH_SCAN_USAGE_COUNT, 0).coerceAtLeast(0)
        } else {
            0
        }
        val nextCount = minOf(limit, currentCount + 1)

        prefs.edit()
            .putString(KEY_BREACH_SCAN_USAGE_DAY_UTC, dayKey)
            .putInt(KEY_BREACH_SCAN_USAGE_COUNT, nextCount)
            .apply()
    }

    fun hasFeatureContinuousScan(context: Context, model: PricingPolicyModel = load(context)): Boolean {
        return resolveFeatureAccess(context, model).features.continuousScanEnabled
    }

    fun hasFeatureOverlayAssistant(context: Context, model: PricingPolicyModel = load(context)): Boolean {
        return resolveFeatureAccess(context, model).features.overlayAssistantEnabled
    }

    fun hasFeatureRotationQueue(context: Context, model: PricingPolicyModel = load(context)): Boolean {
        return resolveFeatureAccess(context, model).features.rotationQueueEnabled
    }

    fun hasFeatureAiHotline(context: Context, model: PricingPolicyModel = load(context)): Boolean {
        return resolveFeatureAccess(context, model).features.aiHotlineEnabled
    }

    fun selectedPlan(context: Context): String {
        if (entitlement(context).isLifetimePro) {
            return "lifetime"
        }
        return rawSelectedPlan(context)
    }

    fun saveSelectedPlan(context: Context, planId: String) {
        if (entitlement(context).isLifetimePro) {
            return
        }
        val normalized = when (planId.lowercase(Locale.US)) {
            "weekly", "monthly", "yearly", "family", "none" -> planId.lowercase(Locale.US)
            else -> "none"
        }
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val editor = prefs.edit().putString(KEY_SELECTED_PLAN, normalized)
        if (normalized == "none") {
            editor.remove(KEY_SELECTED_PLAN_AT)
        } else {
            editor.putLong(KEY_SELECTED_PLAN_AT, System.currentTimeMillis())
        }
        editor.apply()
    }

    fun planSelectedAtEpochMs(context: Context): Long {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_SELECTED_PLAN_AT, 0L).coerceAtLeast(0L)
    }

    fun nextPaymentDue(
        context: Context,
        model: PricingPolicyModel = load(context),
        nowEpochMs: Long = System.currentTimeMillis()
    ): NextPaymentDueStatus {
        val entitlement = entitlement(context)
        if (entitlement.isLifetimePro) {
            return NextPaymentDueStatus(
                statusCode = "lifetime",
                planId = "lifetime",
                dueAtEpochMs = 0L
            )
        }

        val selectedPlan = rawSelectedPlan(context).trim().lowercase(Locale.US)
        if (selectedPlan == "weekly" || selectedPlan == "monthly" || selectedPlan == "yearly" || selectedPlan == "family") {
            val selectedAt = planSelectedAtEpochMs(context).let { stored ->
                if (stored > 0L && stored <= nowEpochMs) stored else nowEpochMs
            }
            val interval = when (selectedPlan) {
                "weekly" -> 7L * DAY_MS
                "monthly" -> 30L * DAY_MS
                "yearly" -> 365L * DAY_MS
                "family" -> 30L * DAY_MS
                else -> 0L
            }
            return NextPaymentDueStatus(
                statusCode = "scheduled",
                planId = selectedPlan,
                dueAtEpochMs = selectedAt + interval
            )
        }

        val trial = ensureTrial(context)
        if (trial.inTrial) {
            return NextPaymentDueStatus(
                statusCode = "trial",
                planId = "none",
                dueAtEpochMs = trial.startedAtEpochMs + (model.freeTrialDays * DAY_MS)
            )
        }

        return NextPaymentDueStatus(
            statusCode = "none",
            planId = "none",
            dueAtEpochMs = 0L
        )
    }

    fun formatMoney(currencyCode: String, amount: Double): String {
        val formatter = NumberFormat.getCurrencyInstance(Locale.US)
        runCatching { formatter.currency = Currency.getInstance(currencyCode) }
        return formatter.format(amount)
    }

    private fun computeTrialStatus(startedAt: Long, now: Long, trialDays: Int): TrialStatus {
        val elapsedDays = max(0L, (now - startedAt) / DAY_MS).toInt()
        val remaining = max(0, trialDays - elapsedDays)
        return TrialStatus(
            startedAtEpochMs = startedAt,
            daysElapsed = elapsedDays,
            daysRemaining = remaining,
            inTrial = elapsedDays < trialDays
        )
    }

    private fun readSettingsPayload(context: Context): JSONObject? {
        val localOverride = File(context.filesDir, SETTINGS_FILE_NAME)
        val content = when {
            localOverride.exists() -> runCatching { localOverride.readText() }.getOrNull()
            else -> runCatching {
                context.assets.open(SETTINGS_FILE_NAME).bufferedReader().use { it.readText() }
            }.getOrNull()
        } ?: return null

        return runCatching { JSONObject(content) }.getOrNull()
    }

    private fun parseCompetitors(array: JSONArray?): List<CompetitorPricePoint> {
        if (array == null) {
            return emptyList()
        }
        val rows = mutableListOf<CompetitorPricePoint>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            if (name.isBlank()) {
                continue
            }
            rows += CompetitorPricePoint(
                name = name,
                monthlyUsd = item.optDouble("monthly_usd", 0.0),
                sourceUrl = item.optString("source_url"),
                observedAt = item.optString("observed_at")
            )
        }
        return rows
    }

    private fun parseRegionalRules(array: JSONArray?): List<RegionalPricingRule> {
        if (array == null) {
            return emptyList()
        }

        val rows = mutableListOf<RegionalPricingRule>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val region = item.optString("region").trim().uppercase(Locale.US)
            val currency = item.optString("currency").trim().uppercase(Locale.US)
            if (region.isBlank() || currency.isBlank()) {
                continue
            }
            rows += RegionalPricingRule(
                regionCode = region,
                regionLabel = item.optString("label").ifBlank { region },
                currencyCode = currency,
                usdToLocalRate = item.optDouble("usd_to_local_rate", 1.0).coerceAtLeast(0.01),
                affordabilityFactor = item.optDouble("affordability_factor", 1.0).coerceAtLeast(0.1),
                countries = parseCountries(item.optJSONArray("countries"))
            )
        }
        return rows
    }

    private fun parseFeatureAccessTier(item: JSONObject?, defaults: FeatureAccessTier): FeatureAccessTier {
        if (item == null) {
            return defaults
        }
        return FeatureAccessTier(
            credentialRecordsLimit = item.optInt(
                "credential_records_limit",
                defaults.credentialRecordsLimit
            ).coerceAtLeast(-1),
            queueActionsLimit = item.optInt(
                "queue_actions_limit",
                defaults.queueActionsLimit
            ).coerceAtLeast(-1),
            breachScansPerDay = item.optInt(
                "breach_scans_per_day",
                defaults.breachScansPerDay
            ).coerceAtLeast(-1),
            continuousScanEnabled = item.optBoolean(
                "continuous_scan_enabled",
                defaults.continuousScanEnabled
            ),
            overlayAssistantEnabled = item.optBoolean(
                "overlay_assistant_enabled",
                defaults.overlayAssistantEnabled
            ),
            rotationQueueEnabled = item.optBoolean(
                "rotation_queue_enabled",
                defaults.rotationQueueEnabled
            ),
            aiHotlineEnabled = item.optBoolean(
                "ai_hotline_enabled",
                defaults.aiHotlineEnabled
            )
        )
    }

    private fun parseFamilyAgeProtocolPolicy(item: JSONObject?): FamilyAgeProtocolPolicy {
        if (item == null) {
            return defaultFamilyAgePolicy
        }
        val bands = parseFamilyAgeBands(item.optJSONArray("groups"))
        return FamilyAgeProtocolPolicy(
            enabled = item.optBoolean("enabled", defaultFamilyAgePolicy.enabled),
            graduationAge = item.optInt(
                "graduation_age",
                defaultFamilyAgePolicy.graduationAge
            ).coerceIn(16, 25),
            parentControlsPlanOptOut = item.optBoolean(
                "parent_controls_plan_opt_out",
                defaultFamilyAgePolicy.parentControlsPlanOptOut
            ),
            bands = if (bands.isEmpty()) defaultFamilyAgePolicy.bands else bands
        )
    }

    private fun parseFamilyAgeBands(array: JSONArray?): List<FamilyAgeBand> {
        if (array == null) {
            return emptyList()
        }
        val rows = mutableListOf<FamilyAgeBand>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val code = item.optString("code").trim().ifBlank { "band_$index" }
            val label = item.optString("label").trim().ifBlank { code }
            val minAge = item.optInt("min_age", -1).coerceAtLeast(0)
            val maxAge = item.optInt("max_age", -1).coerceAtLeast(0)
            if (minAge > maxAge) {
                continue
            }
            rows += FamilyAgeBand(
                code = code,
                label = label,
                minAge = minAge,
                maxAge = maxAge,
                allowStoredCredentialCopy = item.optBoolean("allow_stored_credential_copy", false),
                allowOverlayAssistant = item.optBoolean("allow_overlay_assistant", false),
                requireGuardianApprovalForSensitiveActions = item.optBoolean(
                    "require_guardian_approval_for_sensitive_actions",
                    true
                )
            )
        }
        return rows.sortedBy { it.minAge }
    }

    private fun parseCountries(array: JSONArray?): Set<String> {
        if (array == null) {
            return emptySet()
        }
        val values = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim().uppercase(Locale.US)
            if (value.length == 2) {
                values += value
            }
        }
        return values
    }

    private fun parseHashAllowlist(array: JSONArray?): Set<String> {
        if (array == null) {
            return emptySet()
        }
        val rows = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim().lowercase(Locale.US)
            if (value.matches(Regex("^[a-f0-9]{64}$"))) {
                rows += value
            }
        }
        return rows
    }

    private fun resolveFamilyRoleContext(
        context: Context,
        model: PricingPolicyModel
    ): FamilyRoleContext {
        val selectedPlan = rawSelectedPlan(context).trim().lowercase(Locale.US)
        val profile = PrimaryIdentityStore.readProfile(context)
        val baseRole = normalizeProfileRole(profile.familyRole)
        val inFamilyPlan = selectedPlan == "family"
        if (baseRole == ROLE_PARENT) {
            return FamilyRoleContext(
                roleCode = ROLE_PARENT,
                ageYears = -1,
                ageBand = null,
                familyPlanUmbrella = inFamilyPlan,
                graduatedToFamilySingle = false
            )
        }

        val ageYears = PrimaryIdentityStore.resolveAgeYears(profile)
        val band = resolveAgeBand(model.familyAgePolicy, ageYears)
        val graduated = model.familyAgePolicy.enabled &&
            inFamilyPlan &&
            ageYears >= model.familyAgePolicy.graduationAge
        val roleCode = if (graduated) ROLE_FAMILY_SINGLE else ROLE_CHILD
        return FamilyRoleContext(
            roleCode = roleCode,
            ageYears = ageYears,
            ageBand = band,
            familyPlanUmbrella = inFamilyPlan,
            graduatedToFamilySingle = graduated
        )
    }

    private fun resolveAgeBand(policy: FamilyAgeProtocolPolicy, ageYears: Int): FamilyAgeBand? {
        if (ageYears < 0) {
            return null
        }
        if (!policy.enabled) {
            return defaultFamilyAgePolicy.bands.firstOrNull { ageYears in it.minAge..it.maxAge }
        }
        return policy.bands.firstOrNull { ageYears in it.minAge..it.maxAge }
            ?: policy.bands.lastOrNull()
    }

    private fun normalizeProfileRole(raw: String): String {
        return when (raw.trim().lowercase(Locale.US)) {
            "son", "kid", ROLE_CHILD -> ROLE_CHILD
            ROLE_FAMILY_SINGLE -> ROLE_CHILD
            else -> ROLE_PARENT
        }
    }

    private fun rawSelectedPlan(context: Context): String {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SELECTED_PLAN, "none").orEmpty()
    }

    private fun hasPaidPlanSelected(context: Context): Boolean {
        val plan = rawSelectedPlan(context).trim().lowercase(Locale.US)
        return plan == "weekly" || plan == "monthly" || plan == "yearly" || plan == "family"
    }

    private fun utcDayKey(epochMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(epochMs)
    }

    private fun isDeviceAllowlistedForLifetime(context: Context): Boolean {
        val payload = readSettingsPayload(context) ?: return false
        val pricing = payload.optJSONObject("pricing") ?: return false
        val lifetime = pricing.optJSONObject("lifetime_pro") ?: return false
        if (!lifetime.optBoolean("enabled", false)) {
            return false
        }
        val allowlist = parseHashAllowlist(lifetime.optJSONArray("allowlisted_android_id_sha256"))
        if (allowlist.isEmpty()) {
            return false
        }
        val androidIdHash = hashedAndroidId(context) ?: return false
        return allowlist.contains(androidIdHash)
    }

    private fun hashedAndroidId(context: Context): String? {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty().trim().lowercase(Locale.US)
        if (androidId.isBlank()) {
            return null
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(androidId.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            String.format(Locale.US, "%02x", byte)
        }
    }

    private fun roundForCurrency(currencyCode: String, amount: Double): Double {
        val fractionDigits = runCatching { Currency.getInstance(currencyCode).defaultFractionDigits }
            .getOrDefault(2)
            .coerceIn(0, 4)
        return BigDecimal(amount).setScale(fractionDigits, RoundingMode.HALF_UP).toDouble()
    }
}
