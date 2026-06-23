package com.nutrizeka.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private var pendingFileChooserParams: WebChromeClient.FileChooserParams? = null

    companion object {
        private const val FILE_CHOOSER_REQUEST = 2001
        private const val PERMISSION_REQUEST   = 2002
        private const val URL = "https://yrnklc2017.github.io/Nutri_Zeka/"
    }

    // ── İzin listesi (Android sürümüne göre dinamik) ─────────────────
    private val requiredPermissions: Array<String>
        get() {
            val list = mutableListOf(Manifest.permission.CAMERA)
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> { // API 34+
                    list += Manifest.permission.READ_MEDIA_IMAGES
                    list += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {          // API 33
                    list += Manifest.permission.READ_MEDIA_IMAGES
                }
                else -> {                                                             // API ≤ 32
                    list += Manifest.permission.READ_EXTERNAL_STORAGE
                }
            }
            return list.toTypedArray()
        }

    // ── Activity Result API (modern izin alma) ────────────────────────
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.filter { !it.value }.keys
            if (denied.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "İzin gerekli: Ayarlar > Uygulamalar > NutriZeka > İzinler",
                    Toast.LENGTH_LONG
                ).show()
            }
            // İzin sonrası bekleyen file chooser varsa aç
            pendingFileChooserParams?.let {
                pendingFileChooserParams = null
                openFileChooser(it)
            }
        }

    // ── onCreate ──────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        progressBar  = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        webView      = findViewById(R.id.webView)

        setupWebView()
        setupSwipeRefresh()
        checkAndRequestPermissions()

        webView.loadUrl(URL)
    }

    // ── WebView kurulumu ──────────────────────────────────────────────
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled              = true
            domStorageEnabled              = true
            databaseEnabled                = true
            allowFileAccess                = true
            allowContentAccess             = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode               = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode                      = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            useWideViewPort                = true
            loadWithOverviewMode           = true
            javaScriptCanOpenWindowsAutomatically = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = NutriWebViewClient()
        webView.webChromeClient = NutriWebChromeClient()
    }

    // ── WebViewClient ─────────────────────────────────────────────────
    private inner class NutriWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            progressBar.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            progressBar.visibility = View.GONE
            swipeRefresh.isRefreshing = false
        }

        override fun onReceivedError(
            view: WebView?, request: WebResourceRequest?, error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                loadOfflinePage()
            }
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?, request: WebResourceRequest?
        ): Boolean {
            val url = request?.url?.toString() ?: return false
            // GitHub Pages dışındaki linkleri dış tarayıcıda aç
            return if (!url.startsWith("https://yrnklc2017.github.io")) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                true
            } else false
        }
    }

    // ── WebChromeClient ───────────────────────────────────────────────
    private inner class NutriWebChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progressBar.progress = newProgress
            progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
        }

        // WebRTC kamera/mikrofon izni (tarayıcı seviyesi)
        override fun onPermissionRequest(request: PermissionRequest?) {
            runOnUiThread { request?.grant(request.resources) }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest?) {
            super.onPermissionRequestCanceled(request)
        }

        // ── Dosya seçici ──────────────────────────────────────────────
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            // Önceki callback'i iptal et
            this@MainActivity.filePathCallback?.onReceiveValue(null)
            this@MainActivity.filePathCallback = filePathCallback

            // İzin kontrolü: eksik izin varsa önce al
            val missing = requiredPermissions.filter {
                ContextCompat.checkSelfPermission(this@MainActivity, it) !=
                        PackageManager.PERMISSION_GRANTED
            }
            return if (missing.isNotEmpty()) {
                pendingFileChooserParams = fileChooserParams
                permissionLauncher.launch(missing.toTypedArray())
                true
            } else {
                openFileChooser(fileChooserParams)
                true
            }
        }
    }

    // ── Dosya/kamera seçici ───────────────────────────────────────────
    private fun openFileChooser(params: WebChromeClient.FileChooserParams?) {
        val intents = mutableListOf<Intent>()

        // 1. Kamera intenti
        val photoFile = runCatching { createImageFile() }.getOrNull()
        if (photoFile != null) {
            cameraImageUri = FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", photoFile
            )
            intents += Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        // 2. Video çekme
        intents += Intent(MediaStore.ACTION_VIDEO_CAPTURE)

        // 3. Galeri — Android 13+ için Photo Picker
        val galleryIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
            }
        } else {
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                type = "image/*"
            }
        }

        // 4. Genel dosya seçici (fallback)
        val fileIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }

        val chooser = Intent.createChooser(fileIntent, "Fotoğraf Seç").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, (intents + galleryIntent).toTypedArray())
        }

        startActivityForResult(chooser, FILE_CHOOSER_REQUEST)
    }

    private fun createImageFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: filesDir
        return File.createTempFile("NZ_${stamp}_", ".jpg", dir)
    }

    // ── Activity result (eski API — WebView için gerekli) ─────────────
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FILE_CHOOSER_REQUEST) return

        val cb = filePathCallback ?: return
        filePathCallback = null

        if (resultCode != Activity.RESULT_OK) {
            cb.onReceiveValue(null)
            return
        }

        val uris: Array<Uri>? = when {
            // Veri geldi (galeri / dosya seçici)
            data?.data != null -> arrayOf(data.data!!)
            // Birden fazla seçim
            data?.clipData != null -> {
                val clip = data.clipData!!
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            }
            // Kamera (EXTRA_OUTPUT'a kaydedildi)
            cameraImageUri != null -> {
                val uri = cameraImageUri
                cameraImageUri = null
                arrayOf(uri!!)
            }
            else -> null
        }

        cb.onReceiveValue(uris)
    }

    // ── İzin yönetimi ─────────────────────────────────────────────────
    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ── Offline sayfası ───────────────────────────────────────────────
    private fun loadOfflinePage() {
        val html = """
            <!DOCTYPE html><html><head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              body{font-family:sans-serif;display:flex;flex-direction:column;
                   align-items:center;justify-content:center;min-height:100vh;
                   margin:0;background:#f0faf4;text-align:center;padding:24px}
              h2{color:#16a34a;margin-bottom:8px}
              p{color:#6b7280;margin-bottom:24px;line-height:1.6}
              button{background:#16a34a;color:#fff;border:none;padding:14px 32px;
                     border-radius:12px;font-size:16px;font-weight:700;cursor:pointer}
            </style></head><body>
            <div style="font-size:64px;margin-bottom:16px">📡</div>
            <h2>İnternet Bağlantısı Yok</h2>
            <p>NutriZeka'ya bağlanmak için<br>internet bağlantınızı kontrol edin.</p>
            <button onclick="location.reload()">Tekrar Dene</button>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    // ── Swipe to refresh ─────────────────────────────────────────────
    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, android.R.color.holo_green_dark)
        )
        swipeRefresh.setOnRefreshListener { webView.reload() }
    }

    // ── Geri tuşu ────────────────────────────────────────────────────
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onResume()  { super.onResume();  webView.onResume()  }
    override fun onPause()   { super.onPause();   webView.onPause()   }
    override fun onDestroy() { webView.destroy(); super.onDestroy()   }
}
