package com.deposplit.ui.paywall

import androidx.lifecycle.ViewModel
import com.deposplit.driven_ports.PurchaseRepository
import com.deposplit.value_objects.SecretLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PaywallViewModel(purchases: PurchaseRepository) : ViewModel() {

    data class UiState(
        val isPremium: Boolean,
        val freeTierLimit: Int = SecretLimits.FREE_TIER_MAX_ACTIVE_SECRETS,
        // Constant for now, and the screen says so rather than offering a button that cannot work:
        // Google Play Billing needs a Play Console listing for com.deposplit before it can run at
        // all, so there is nothing behind a purchase on Android yet. Restoring is the same story —
        // it is queryPurchasesAsync, which is equally unreachable.
        val isPurchaseAvailable: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState(isPremium = purchases.isPremium()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
}
