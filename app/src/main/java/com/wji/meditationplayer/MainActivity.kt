package com.wji.meditationplayer

import android.Manifest
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.wji.meditationplayer.ui.MeditationTheme
import com.wji.meditationplayer.ui.home.HomeScreen
import com.wji.meditationplayer.ui.home.HomeViewModel
import com.wji.meditationplayer.ui.player.PlayerScreen
import com.wji.meditationplayer.ui.player.PlayerViewModel

private const val ROUTE_HOME = "home"
private const val ROUTE_PLAYER = "player"
private const val ARG_FILE_KEY = "fileKey"

@UnstableApi
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MeditationTheme {
                Surface {
                    // Android 15+ 強制 edge-to-edge，不留這層 padding 內容會被狀態列蓋住。
                    Box(Modifier.safeDrawingPadding()) {
                        RequestNotificationPermission()
                        AppNavHost()
                    }
                }
            }
        }
    }
}

/** Android 13+ 需要這個權限才能顯示播放通知。 */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

@UnstableApi
@Composable
private fun AppNavHost() {
    val navController = rememberNavController()
    val application = LocalContext.current.applicationContext as Application

    NavHost(navController = navController, startDestination = ROUTE_HOME) {
        composable(ROUTE_HOME) {
            val homeViewModel: HomeViewModel = viewModel(
                factory = viewModelFactory { initializer { HomeViewModel(application) } },
            )
            HomeScreen(
                viewModel = homeViewModel,
                onOpenTrack = { fileKey -> navController.navigate("$ROUTE_PLAYER/$fileKey") },
            )
        }
        composable("$ROUTE_PLAYER/{$ARG_FILE_KEY}") { backStackEntry ->
            val fileKey = backStackEntry.arguments?.getString(ARG_FILE_KEY)
            if (fileKey != null) {
                val playerViewModel: PlayerViewModel = viewModel(
                    key = fileKey,
                    factory = viewModelFactory {
                        initializer { PlayerViewModel(application, fileKey) }
                    },
                )
                PlayerScreen(viewModel = playerViewModel)
            }
        }
    }
}
