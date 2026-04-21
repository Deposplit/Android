package com.deposplit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.deposplit.ui.contacts.AddContactScreen
import com.deposplit.ui.contacts.ContactsScreen
import com.deposplit.ui.deposit.DepositScreen
import com.deposplit.ui.home.HomeScreen
import com.deposplit.ui.sharedetail.ShareDetailScreen
import com.deposplit.ui.signin.SignInScreen
import com.deposplit.ui.theme.DeposplitTheme
import java.util.UUID

private const val ROUTE_SIGN_IN = "sign_in"
private const val ROUTE_HOME = "home"
private const val ROUTE_CONTACTS = "contacts"
private const val ROUTE_ADD_CONTACT = "add_contact"
private const val ROUTE_DEPOSIT = "deposit"
private const val ROUTE_SHARE_DETAIL = "share_detail/{shareId}"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as DeposplitApp
        val startDestination = if (app.authAdapter.isRegistered()) ROUTE_HOME else ROUTE_SIGN_IN

        setContent {
            DeposplitTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = startDestination) {
                    composable(ROUTE_SIGN_IN) {
                        SignInScreen(
                            onNavigateToHome = {
                                navController.navigate(ROUTE_HOME) {
                                    popUpTo(ROUTE_SIGN_IN) { inclusive = true }
                                }
                            }
                        )
                    }
                    composable(ROUTE_HOME) {
                        HomeScreen(
                            onNavigateToContacts = { navController.navigate(ROUTE_CONTACTS) },
                            onNavigateToDeposit = { navController.navigate(ROUTE_DEPOSIT) },
                            onNavigateToShareDetail = { shareId ->
                                navController.navigate("share_detail/$shareId")
                            },
                        )
                    }
                    composable(ROUTE_DEPOSIT) {
                        DepositScreen(onNavigateBack = { navController.popBackStack() })
                    }
                    composable(ROUTE_CONTACTS) {
                        ContactsScreen(
                            onNavigateToAddContact = { navController.navigate(ROUTE_ADD_CONTACT) }
                        )
                    }
                    composable(ROUTE_ADD_CONTACT) {
                        AddContactScreen(
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable(ROUTE_SHARE_DETAIL) { backStackEntry ->
                        val shareId = UUID.fromString(
                            backStackEntry.arguments?.getString("shareId")
                        )
                        ShareDetailScreen(
                            shareId = shareId,
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
