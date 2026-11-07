package io.github.webviewtemplate

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.util.Log
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import androidx.core.graphics.toColorInt

@SuppressLint("IntentReset", "QueryPermissionsNeeded")
class MainActivity : AppCompatActivity() {
    private data class PendingWebPermissionRequest(
        val request: PermissionRequest,
        val resources: Array<String>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PendingWebPermissionRequest

            if (request != other.request) return false
            if (!resources.contentEquals(other.resources)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = request.hashCode()
            result = 31 * result + resources.contentHashCode()
            return result
        }
    }

    private lateinit var webView: WebView
    private lateinit var rootContainer: FrameLayout
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraCaptureUri: Uri? = null
    private var pendingCameraCaptureFile: File? = null
    private var pendingWebPermissionRequest: PendingWebPermissionRequest? = null
    private val webPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val pending = pendingWebPermissionRequest ?: return@registerForActivityResult
            pendingWebPermissionRequest = null

            val grantedResources = pending.resources.filter { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                        checkSelfPermission(Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                    else -> false
                }
            }.toTypedArray()

            Log.d(
                TAG,
                "Runtime permission result. requestedResources=${pending.resources.contentToString()} " +
                    "grantedResources=${grantedResources.contentToString()}"
            )
            if (grantedResources.isNotEmpty()) {
                pending.request.grant(pending.resources)
            } else {
                pending.request.deny()
            }
        }
    private val localNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "Local network permission result. granted=$granted")
            // Requests made before the grant were blocked; reload so the page can retry them.
            if (granted) {
                webView.reload()
            }
        }
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback ?: return@registerForActivityResult
            val cameraUri = pendingCameraCaptureUri
            val uris = when {
                result.resultCode != RESULT_OK -> null
                cameraUri != null &&
                    result.data?.data == null &&
                    result.data?.clipData == null -> arrayOf(cameraUri)
                else -> WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            }
            callback.onReceiveValue(uris)
            fileChooserCallback = null
            clearPendingCameraCapture(deleteFile = result.resultCode != RESULT_OK)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContentView(R.layout.activity_main)

        rootContainer = findViewById(R.id.root_container)
        webView = findViewById(R.id.webview)
        val initialLeft = rootContainer.paddingLeft
        val initialTop = rootContainer.paddingTop
        val initialRight = rootContainer.paddingRight
        val initialBottom = rootContainer.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                initialLeft + systemBars.left,
                initialTop + systemBars.top,
                initialRight + systemBars.right,
                initialBottom + systemBars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(rootContainer)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    Log.d(
                        TAG,
                        "Console[${consoleMessage.messageLevel()}] " +
                            "${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} " +
                            consoleMessage.message()
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) {
                    return
                }
                runOnUiThread {
                    Log.d(
                        TAG,
                        "onPermissionRequest origin=${request.origin} resources=${request.resources.contentToString()}"
                    )
                    val requestedResources = request.resources.filter {
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                    }.toTypedArray()
                    if (requestedResources.isEmpty()) {
                        Log.d(TAG, "Deny permission request: no supported resources")
                        request.deny()
                        return@runOnUiThread
                    }

                    val missingPermissions = mutableSetOf<String>()
                    if (
                        requestedResources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                            PackageManager.PERMISSION_GRANTED
                    ) {
                        missingPermissions += Manifest.permission.RECORD_AUDIO
                    }
                    if (
                        requestedResources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
                        checkSelfPermission(Manifest.permission.CAMERA) !=
                            PackageManager.PERMISSION_GRANTED
                    ) {
                        missingPermissions += Manifest.permission.CAMERA
                    }

                    if (missingPermissions.isEmpty()) {
                        Log.d(TAG, "Grant web permission directly for resources=${requestedResources.contentToString()}")
                        request.grant(requestedResources)
                    } else {
                        pendingWebPermissionRequest?.request?.deny()
                        pendingWebPermissionRequest =
                            PendingWebPermissionRequest(request, requestedResources)
                        Log.d(
                            TAG,
                            "Request runtime permissions for ${missingPermissions.toTypedArray().contentToString()}"
                        )
                        webPermissionLauncher.launch(missingPermissions.toTypedArray())
                    }
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                Log.d(
                    TAG,
                    "onPermissionRequestCanceled origin=${request?.origin} resources=${request?.resources?.contentToString()}"
                )
                super.onPermissionRequestCanceled(request)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null) {
                    return false
                }
                Log.d(TAG, "onShowFileChooser called")
                fileChooserCallback?.onReceiveValue(null)
                clearPendingCameraCapture(deleteFile = true)
                fileChooserCallback = filePathCallback

                val chooserIntent = runCatching {
                    buildFileChooserIntent(fileChooserParams)
                }.getOrElse {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                    clearPendingCameraCapture(deleteFile = true)
                    return false
                }

                return runCatching {
                    fileChooserLauncher.launch(chooserIntent)
                    true
                }.getOrElse {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                    clearPendingCameraCapture(deleteFile = true)
                    false
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                syncTopInsetColorWithPage(view)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return false
            }
        }

        webView.loadUrl(HOME_URL)
        // A recreated activity may already have a request in flight; its result is redelivered.
        if (savedInstanceState == null) {
            requestLocalNetworkPermissionIfNeeded()
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    override fun onDestroy() {
        pendingWebPermissionRequest?.request?.deny()
        pendingWebPermissionRequest = null
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        clearPendingCameraCapture(deleteFile = true)
        webView.destroy()
        super.onDestroy()
    }

    override fun onStop() {
        CookieManager.getInstance().flush()
        super.onStop()
    }

    /**
     * Android 17 blocks local network traffic for apps targeting SDK 37 unless
     * ACCESS_LOCAL_NETWORK is granted. WebView traffic inherits the app's permission
     * state and offers no PermissionRequest resource for it, so the app asks up front.
     * Removing the permission from the manifest opts out of the request.
     */
    private fun requestLocalNetworkPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
            !isPermissionDeclared(Manifest.permission.ACCESS_LOCAL_NETWORK) ||
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        Log.d(TAG, "Request runtime permission for ${Manifest.permission.ACCESS_LOCAL_NETWORK}")
        localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun isPermissionDeclared(permission: String): Boolean {
        val packageInfo = packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
        )
        return packageInfo.requestedPermissions?.contains(permission) == true
    }

    private fun buildFileChooserIntent(fileChooserParams: WebChromeClient.FileChooserParams?): Intent {
        val baseIntent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }

        val acceptTypes = parseAcceptTypes(fileChooserParams)
        val supportsImage = acceptTypes.isEmpty() ||
            acceptTypes.any { acceptType ->
                acceptType == "*/*" || acceptType.startsWith("image/") || acceptType == ".jpg" ||
                    acceptType == ".jpeg" || acceptType == ".png" || acceptType == ".webp"
            }

        val extraIntents = mutableListOf<Intent>()
        if (supportsImage) {
            createGalleryIntent()?.let { extraIntents += it }
            createCameraCaptureIntent()?.let { extraIntents += it }
        }

        return Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, baseIntent)
            putExtra(Intent.EXTRA_TITLE, "Select source")
            if (extraIntents.isNotEmpty()) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents.toTypedArray())
            }
        }
    }

    private fun createGalleryIntent(): Intent? {
        val galleryIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        return if (galleryIntent.resolveActivity(packageManager) != null) galleryIntent else null
    }

    private fun createCameraCaptureIntent(): Intent? {
        val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (captureIntent.resolveActivity(packageManager) == null) {
            return null
        }

        val photoDir = File(cacheDir, "captured").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val photoFile = File(photoDir, "IMG_${timestamp}.jpg")
        val authority = "${packageName}.fileprovider"
        val photoUri = FileProvider.getUriForFile(this, authority, photoFile)

        pendingCameraCaptureFile = photoFile
        pendingCameraCaptureUri = photoUri

        captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        captureIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return captureIntent
    }

    private fun parseAcceptTypes(fileChooserParams: WebChromeClient.FileChooserParams?): List<String> {
        return fileChooserParams
            ?.acceptTypes
            ?.flatMap { it.split(",") }
            ?.map { it.trim().lowercase(Locale.ROOT) }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    private fun clearPendingCameraCapture(deleteFile: Boolean) {
        if (deleteFile) {
            pendingCameraCaptureFile?.takeIf { it.exists() }?.delete()
        }
        pendingCameraCaptureUri = null
        pendingCameraCaptureFile = null
    }

    private fun syncTopInsetColorWithPage(view: WebView) {
        view.evaluateJavascript(PAGE_BACKGROUND_JS) { jsValue ->
            val cssColor = decodeJsonString(jsValue) ?: return@evaluateJavascript
            val color = parseCssColor(cssColor) ?: return@evaluateJavascript
            rootContainer.setBackgroundColor(color)

            val useDarkStatusBarIcons = ColorUtils.calculateLuminance(color) > 0.5
            WindowInsetsControllerCompat(window, rootContainer).isAppearanceLightStatusBars =
                useDarkStatusBarIcons
        }
    }

    private fun decodeJsonString(value: String): String? {
        return runCatching { JSONArray("[$value]").getString(0) }.getOrNull()
    }

    private fun parseCssColor(input: String): Int? {
        val value = input.trim().lowercase()
        if (value == "transparent" || value == "rgba(0, 0, 0, 0)") {
            return Color.WHITE
        }
        runCatching { value.toColorInt() }.getOrNull()?.let { return it }

        val rgbRegex =
            Regex("""rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})(?:\s*,\s*([0-9.]+))?\s*\)""")
        val match = rgbRegex.matchEntire(value) ?: return null
        val r = match.groupValues[1].toInt().coerceIn(0, 255)
        val g = match.groupValues[2].toInt().coerceIn(0, 255)
        val b = match.groupValues[3].toInt().coerceIn(0, 255)
        val alpha = match.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() }?.toFloatOrNull()
        return if (alpha == null) {
            Color.rgb(r, g, b)
        } else {
            Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), r, g, b)
        }
    }

    companion object {
        private const val TAG = "WebViewTemplate"
        private const val HOME_URL = BuildConfig.HOME_URL
        private const val PAGE_BACKGROUND_JS = """
            (() => {
              const htmlColor = window.getComputedStyle(document.documentElement).backgroundColor;
              const bodyColor = window.getComputedStyle(document.body).backgroundColor;
              const candidates = [bodyColor, htmlColor];
              for (const color of candidates) {
                if (!color) continue;
                const normalized = color.trim().toLowerCase();
                if (normalized !== "transparent" && normalized !== "rgba(0, 0, 0, 0)") {
                  return color;
                }
              }
              return "#ffffff";
            })();
        """
    }
}
