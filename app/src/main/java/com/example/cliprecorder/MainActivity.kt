package com.example.cliprecorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.cliprecorder.ui.CameraScreen
import com.example.cliprecorder.ui.ClipListScreen
import com.example.cliprecorder.ui.EditScreen
import com.example.cliprecorder.ui.MergePreviewScreen
import com.example.cliprecorder.ui.SettingsScreen
import com.example.cliprecorder.viewmodel.CameraViewModel
import com.example.cliprecorder.widget.RecordWidgetProvider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startRecord = intent?.action == RecordWidgetProvider.ACTION_START_RECORD
        setContent {
            MaterialTheme {
                ClipRecorderApp(startRecord = startRecord)
            }
        }
    }
}

@Composable
fun ClipRecorderApp(startRecord: Boolean = false) {
    val navController = rememberNavController()
    val viewModel: CameraViewModel = viewModel()

    // 必要なパーミッション
    val permissions = listOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    // パーミッション確認
    var allGranted by remember {
        mutableStateOf(
            permissions.all { perm ->
                ContextCompat.checkSelfPermission(
                    navController.context, perm
                ) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        allGranted = results.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!allGranted) launcher.launch(permissions.toTypedArray())
    }

    if (!allGranted) {
        // パーミッション未許可画面
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("カメラとマイクの権限が必要です")
                Spacer(Modifier.height(16.dp))
                Button(onClick = { launcher.launch(permissions.toTypedArray()) }) {
                    Text("権限を許可する")
                }
            }
        }
        return
    }

    NavHost(navController = navController, startDestination = "camera") {
        composable("camera") {
            CameraScreen(
                viewModel = viewModel,
                onNavigateClips = { navController.navigate("clips") },
                onNavigateSettings = { navController.navigate("settings") },
                autoStartRecord = startRecord,
            )
        }
        composable("clips") {
            ClipListScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onNavigateEdit = { navController.navigate("edit") },
                onNavigateMergePreview = { navController.navigate("merge_preview") },
            )
        }
        composable("merge_preview") {
            MergePreviewScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable("edit") {
            EditScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
