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
import android.content.ContentValues
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebSettings
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.graphics.Bitmap
import android.graphics.Canvas

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
    @Volatile private var pageIsReady = false
    private lateinit var rootContainer: FrameLayout
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private val colorHandler = Handler(Looper.getMainLooper())
    private val colorPoller = object : Runnable {
        override fun run() {
            if (::webView.isInitialized) syncBarColorsWithPage(webView)
            colorHandler.postDelayed(this, 600)
        }
    }
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraCaptureUri: Uri? = null
    private var pendingCameraCaptureFile: File? = null
    private var pendingWebPermissionRequest: PendingWebPermissionRequest? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val cb = pendingGeoCallback ?: return@registerForActivityResult
            val origin = pendingGeoOrigin ?: return@registerForActivityResult
            cb.invoke(origin, results.values.any { it }, false)
            pendingGeoCallback = null
            pendingGeoOrigin = null
        }
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
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !pageIsReady }
        super.onCreate(savedInstanceState)
        Handler(Looper.getMainLooper()).postDelayed({ pageIsReady = true }, 10000)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContentView(R.layout.activity_main)

        rootContainer = findViewById(R.id.root_container)
        rootContainer.setBackgroundColor(Color.WHITE)
        webView = findViewById(R.id.webview)

        topBar = View(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 0, Gravity.TOP
            )
        }
        bottomBar = View(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM
            )
        }
        rootContainer.addView(topBar)
        rootContainer.addView(bottomBar)
        topBar.setBackgroundColor(Color.parseColor("#0B0B0B"))
        WindowInsetsControllerCompat(window, rootContainer).isAppearanceLightStatusBars = false

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (webView.layoutParams as FrameLayout.LayoutParams).setMargins(
                bars.left, bars.top, bars.right, bars.bottom
            )
            webView.requestLayout()
            topBar.layoutParams.height = bars.top
            bottomBar.layoutParams.height = bars.bottom
            topBar.requestLayout()
            bottomBar.requestLayout()
            insets
        }
        ViewCompat.requestApplyInsets(rootContainer)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
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

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (origin == null || callback == null) return
                val hasLocation =
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
                if (hasLocation) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeoCallback?.invoke(pendingGeoOrigin ?: origin, false, false)
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
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
                syncBarColorsWithPage(view)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return false
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) pageIsReady = true
            }
        }

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        WebView.setWebContentsDebuggingEnabled(true)
        webView.setDownloadListener { url, _, _, mimeType, _ ->
            if (url.startsWith("data:")) {
                val comma = url.indexOf(',')
                val meta = url.substring(5, comma)
                val payload = url.substring(comma + 1)
                val mime = meta.substringBefore(';').ifEmpty { mimeType ?: "application/octet-stream" }
                val name = Regex("filename=([^;]+)").find(meta)?.groupValues?.get(1)
                    ?.let { Uri.decode(it) } ?: "download.pdf"
                val bytes = if (meta.contains(";base64")) Base64.decode(payload, Base64.DEFAULT)
                            else Uri.decode(payload).toByteArray()
                saveBytes(name, mime, bytes)
            }
        }
                webView.setBackgroundColor(Color.WHITE)
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

        override fun onResume() {
        super.onResume()
        colorHandler.post(colorPoller)
    }

    override fun onPause() {
        colorHandler.removeCallbacks(colorPoller)
        super.onPause()
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
        if (fileChooserParams?.isCaptureEnabled == true) {
            createCameraCaptureIntent()?.let { camera ->
                camera.putExtra("android.intent.extras.CAMERA_FACING", 1)
                camera.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                return camera
            }
        }
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

    private fun syncBarColorsWithPage(view: WebView) {
        if (view.width <= 0 || view.height <= 0) return
        val scale = 0.25f
        val w = (view.width * scale).toInt().coerceAtLeast(4)
        val h = (view.height * scale).toInt().coerceAtLeast(4)
        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            return
        }
        try {
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            view.draw(canvas)
            val bottomPx = bmp.getPixel(2, h - 2)
            if (Color.alpha(bottomPx) == 0) return
            val bottom = Color.rgb(Color.red(bottomPx), Color.green(bottomPx), Color.blue(bottomPx))
            bottomBar.setBackgroundColor(bottom)
            WindowInsetsControllerCompat(window, rootContainer).isAppearanceLightNavigationBars =
                ColorUtils.calculateLuminance(bottom) > 0.5
        } finally {
            bmp.recycle()
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

    private fun saveBytes(name: String, mime: String, bytes: ByteArray) {
        try {
            val uri: Uri
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("insert returned null")
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
                val f = File(dir, name)
                f.writeBytes(bytes)
                uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", f)
            }
            runOnUiThread {
                Toast.makeText(this, "Saved to Downloads: $name", Toast.LENGTH_LONG).show()
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) { }
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveBytes failed", e)
            runOnUiThread { Toast.makeText(this, "Could not save the file", Toast.LENGTH_LONG).show() }
        }
    }

    inner class AndroidBridge {
        private var printView: WebView? = null

        @JavascriptInterface
        fun pageReady() { pageIsReady = true }

            @JavascriptInterface
        fun saveFile(name: String, mime: String, b64: String) {
            saveBytes(name, mime, Base64.decode(b64, Base64.DEFAULT))
        }

        @JavascriptInterface
        fun printHtml(title: String, html: String) {
            runOnUiThread {
                val wv = WebView(this@MainActivity)
                printView = wv
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        val pm = getSystemService(PRINT_SERVICE) as PrintManager
                        pm.print(title, view.createPrintDocumentAdapter(title), PrintAttributes.Builder().build())
                        printView = null
                    }
                }
                wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        }
    }

    companion object {
        private const val TAG = "WebViewTemplate"
        private const val HOME_URL = BuildConfig.HOME_URL
        private const val EDGE_COLORS_JS = """
            (() => {
              const solid = (c) => {
                if (!c) return false;
                const m = c.match(/rgba?\(([^)]+)\)/);
                if (!m) return false;
                const p = m[1].split(',').map(s => parseFloat(s));
                return p.length < 4 || p[3] >= 0.95;
              };
              const colorAt = (y) => {
                let el = document.elementFromPoint(4, y);
                while (el) {
                  const c = getComputedStyle(el).backgroundColor;
                  if (solid(c)) return c;
                  el = el.parentElement;
                }
                return null;
              };
              const b = getComputedStyle(document.body).backgroundColor;
              const h = getComputedStyle(document.documentElement).backgroundColor;
              const fallback = solid(b) ? b : (solid(h) ? h : 'rgb(255, 255, 255)');
              return (colorAt(2) || fallback) + '|' + (colorAt(window.innerHeight - 3) || fallback);
            })();
        """
    }
}

