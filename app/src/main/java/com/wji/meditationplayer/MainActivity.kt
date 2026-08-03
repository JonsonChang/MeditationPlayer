package com.wji.meditationplayer

import android.Manifest
import android.app.Application
import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val ROUTE_HOME = "home"
private const val ROUTE_PLAYER = "player"
private const val ARG_FILE_KEY = "fileKey"

@UnstableApi
class MainActivity : ComponentActivity() {

    /** 播放通知卡片帶進來、還沒導航過去的 fileKey。 */
    private val pendingFileKey = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingFileKey.value = intent.fileKeyExtra()
        setContent {
            MeditationTheme {
                Surface {
                    // Android 15+ 強制 edge-to-edge，不留這層 padding 內容會被狀態列蓋住。
                    Box(Modifier.safeDrawingPadding()) {
                        RequestNotificationPermission()
                        AppNavHost(
                            pendingFileKey = pendingFileKey,
                            onFileKeyConsumed = ::consumeFileKey,
                        )
                    }
                }
            }
        }
    }

    /** singleTask 下既有實例被重用時走這裡（onCreate 不會再跑）。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingFileKey.value = intent.fileKeyExtra()
    }

    /**
     * 導航完就把 extra 從 intent 上拔掉：轉螢幕重建時 `onCreate` 會再讀到同一個 intent，
     * 不拔掉會把已經按返回鍵回到最近清單的使用者彈回播放畫面。
     *
     * 用「消耗 intent」而不是判斷 `savedInstanceState == null`，是因為 activity 被系統回收
     * （前景服務還在播）之後再點卡片時，系統送來的是**新的** intent 但 savedInstanceState
     * 不是 null —— 那種情況仍然必須導航過去。
     */
    private fun consumeFileKey() {
        intent.removeExtra(EXTRA_FILE_KEY)
        pendingFileKey.value = null
    }

    companion object {
        const val EXTRA_FILE_KEY = "com.wji.meditationplayer.extra.FILE_KEY"
    }
}

private fun Intent.fileKeyExtra(): String? =
    getStringExtra(MainActivity.EXTRA_FILE_KEY)?.takeIf { it.isNotEmpty() }

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
private fun AppNavHost(
    pendingFileKey: StateFlow<String?>,
    onFileKeyConsumed: () -> Unit,
) {
    val navController = rememberNavController()
    val application = LocalContext.current.applicationContext as Application

    // 從播放通知進來時疊在 Home 之上（刻意不改 startDestination：返回鍵要能回到最近清單）。
    val requestedFileKey by pendingFileKey.collectAsStateWithLifecycle()
    LaunchedEffect(requestedFileKey) {
        val fileKey = requestedFileKey ?: return@LaunchedEffect
        navController.navigate("$ROUTE_PLAYER/$fileKey") { launchSingleTop = true }
        onFileKeyConsumed()
    }

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
