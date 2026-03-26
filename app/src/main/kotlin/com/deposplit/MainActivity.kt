package com.deposplit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.deposplit.ui.home.HomeScreen
import com.deposplit.ui.signin.SignInScreen
import com.deposplit.ui.theme.DeposplitTheme

private const val ROUTE_SIGN_IN = "sign_in"
private const val ROUTE_HOME = "home"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle OIDC callback that arrived while the app was being created.
        handleOidcCallbackIntent(intent)

        val app = application as DeposplitApp
        val startDestination = if (app.authAdapter.isLoggedIn()) ROUTE_HOME else ROUTE_SIGN_IN

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
                        HomeScreen()
                    }
                }
            }
        }
    }

    // Called when the OIDC browser redirects back to the app via the deep link.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOidcCallbackIntent(intent)
    }

    private fun handleOidcCallbackIntent(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme == "deposplit" && uri.host == "auth") {
            (application as DeposplitApp).onOidcCallback(uri.toString())
        }
    }
}
