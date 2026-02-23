package com.realyn.watchdog

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import java.util.Locale

data class BillingEntitlementRefreshResult(
    val success: Boolean,
    val changed: Boolean,
    val verifiedPlanId: String
)

object PlayBillingEntitlementRefresher {

    private const val KEY_LAST_SYNC_AT = "pricing_billing_last_sync_at_epoch_ms"
    private const val REFRESH_WINDOW_MS = 6L * 60L * 60L * 1000L
    private const val PLAN_NONE = "none"
    private const val PLAN_WEEKLY = "weekly"
    private const val PLAN_MONTHLY = "monthly"
    private const val PLAN_YEARLY = "yearly"
    private const val PLAN_FAMILY = "family"
    private const val PLAN_LIFETIME = "lifetime"

    fun shouldRefresh(context: Context, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        val lastSync = prefs.getLong(KEY_LAST_SYNC_AT, 0L).coerceAtLeast(0L)
        if (lastSync <= 0L || lastSync > nowEpochMs) {
            return true
        }
        return nowEpochMs - lastSync >= REFRESH_WINDOW_MS
    }

    fun refresh(
        activity: AppCompatActivity,
        force: Boolean = false,
        onResult: (BillingEntitlementRefreshResult) -> Unit
    ) {
        if (!force && !shouldRefresh(activity)) {
            dispatch(
                activity,
                BillingEntitlementRefreshResult(
                    success = true,
                    changed = false,
                    verifiedPlanId = resolveCurrentPlanId(activity)
                ),
                onResult
            )
            return
        }

        val billingClient = BillingClient.newBuilder(activity)
            .enablePendingPurchases()
            .setListener { _, _ -> }
            .build()
        val beforeSnapshot = entitlementSnapshot(activity)

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingServiceDisconnected() {
                dispatch(
                    activity,
                    BillingEntitlementRefreshResult(
                        success = false,
                        changed = false,
                        verifiedPlanId = resolveCurrentPlanId(activity)
                    ),
                    onResult
                )
            }

            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    billingClient.endConnection()
                    dispatch(
                        activity,
                        BillingEntitlementRefreshResult(
                            success = false,
                            changed = false,
                            verifiedPlanId = resolveCurrentPlanId(activity)
                        ),
                        onResult
                    )
                    return
                }

                queryPurchased(
                    client = billingClient,
                    type = BillingClient.ProductType.SUBS
                ) { subsSuccess, subsPurchases ->
                    queryPurchased(
                        client = billingClient,
                        type = BillingClient.ProductType.INAPP
                    ) { inAppSuccess, inAppPurchases ->
                        if (!subsSuccess || !inAppSuccess) {
                            billingClient.endConnection()
                            dispatch(
                                activity,
                                BillingEntitlementRefreshResult(
                                    success = false,
                                    changed = false,
                                    verifiedPlanId = resolveCurrentPlanId(activity)
                                ),
                                onResult
                            )
                        } else {
                            val lifetimePurchased = resolveLifetimePurchase(subsPurchases + inAppPurchases)
                            if (lifetimePurchased) {
                                PricingPolicy.grantLifetimePro(activity, source = "play_billing")
                            } else {
                                val planId = resolveSubscriptionPlanId(subsPurchases)
                                if (planId == null) {
                                    PricingPolicy.clearVerifiedPaidPlan(activity)
                                } else {
                                    PricingPolicy.saveVerifiedPaidPlan(
                                        context = activity,
                                        planId = planId,
                                        source = "play_billing"
                                    )
                                }
                            }
                            markSyncNow(activity)
                            val afterSnapshot = entitlementSnapshot(activity)
                            val changed = beforeSnapshot != afterSnapshot
                            val verifiedPlanId = resolveCurrentPlanId(activity)
                            billingClient.endConnection()
                            dispatch(
                                activity,
                                BillingEntitlementRefreshResult(
                                    success = true,
                                    changed = changed,
                                    verifiedPlanId = verifiedPlanId
                                ),
                                onResult
                            )
                        }
                    }
                }
            }
        })
    }

    private fun queryPurchased(
        client: BillingClient,
        type: String,
        onResult: (success: Boolean, purchases: List<Purchase>) -> Unit
    ) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(type)
            .build()
        client.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                onResult(false, emptyList())
                return@queryPurchasesAsync
            }
            onResult(
                true,
                purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            )
        }
    }

    private fun resolveLifetimePurchase(purchases: List<Purchase>): Boolean {
        return purchases
            .flatMap { it.products }
            .map { it.trim().lowercase(Locale.US) }
            .any { product ->
                product.contains("lifetime") || product.contains("lifetime_pro") || product.contains("pro_life")
            }
    }

    private fun resolveSubscriptionPlanId(subscriptions: List<Purchase>): String? {
        val products = subscriptions
            .flatMap { it.products }
            .map { it.trim().lowercase(Locale.US) }
        if (products.isEmpty()) {
            return null
        }
        return when {
            products.any { it.contains("family") } -> PLAN_FAMILY
            products.any { it.contains("year") || it.contains("annual") } -> PLAN_YEARLY
            products.any { it.contains("week") } -> PLAN_WEEKLY
            else -> PLAN_MONTHLY
        }
    }

    private fun resolveCurrentPlanId(context: Context): String {
        if (PricingPolicy.entitlement(context).isLifetimePro) {
            return PLAN_LIFETIME
        }
        return PricingPolicy.verifiedPaidPlan(context)?.planId ?: PLAN_NONE
    }

    private fun entitlementSnapshot(context: Context): String {
        val lifetime = PricingPolicy.entitlement(context)
        if (lifetime.isLifetimePro) {
            return "${PLAN_LIFETIME}:${lifetime.source}"
        }
        val plan = PricingPolicy.verifiedPaidPlan(context)
        return if (plan == null) {
            PLAN_NONE
        } else {
            "${plan.planId}:${plan.source}:${plan.verifiedAtEpochMs}"
        }
    }

    private fun markSyncNow(context: Context) {
        val prefs = context.getSharedPreferences(WatchdogConfig.PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis()).apply()
    }

    private fun dispatch(
        activity: AppCompatActivity,
        result: BillingEntitlementRefreshResult,
        onResult: (BillingEntitlementRefreshResult) -> Unit
    ) {
        activity.runOnUiThread { onResult(result) }
    }
}
