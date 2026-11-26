package com.floatbox.ai

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var themedContext: Context
    private val watchdogHandler = Handler(Looper.getMainLooper())

    private var bubbleView: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams

    private var windowView: View? = null
    private lateinit var windowParams: WindowManager.LayoutParams

    private var isWindowOpen = false
    private var isMaximized = false
    private var isWindowFocusable = false
    private var isCapturingScreen = false

    private var savedWidth = 0
    private var savedHeight = 0
    private var savedX = 0
    private var savedY = 0

    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var webProgressBar: ProgressBar
    private lateinit var panelBrowser: LinearLayout
    private lateinit var panelOcr: ScrollView
    private lateinit var tabBrowser: LinearLayout
    private lateinit var tabOcr: LinearLayout
    private lateinit var ivTabBrowser: ImageView
    private lateinit var tvTabBrowser: TextView
    private lateinit var ivTabOcr: ImageView
    private lateinit var tvTabOcr: TextView
    private lateinit var btnMaximize: ImageButton

    private lateinit var cropImageView: CropImageView
    private lateinit var btnScanCrop: MaterialButton
    private lateinit var switchAutoSearch: SwitchCompat
    private lateinit var ocrProgressBar: ProgressBar
    private lateinit var etOcrResult: EditText

    private val bubbleWatchdog = object : Runnable {
        override fun run() {
            if (isRunning && !isWindowOpen && !isCapturingScreen && Settings.canDrawOverlays(this@FloatingOverlayService)) {
                val bubble = bubbleView
                if (bubble != null) {
                    if (bubble.parent == null) {
                        try {
                            windowManager.addView(bubble, bubbleParams)
                        } catch (_: Exception) {
                        }
                    } else if (bubble.visibility != View.VISIBLE) {
                        bubble.visibility = View.VISIBLE
                    }
                    applyBubbleStyle()
                }
            }
            watchdogHandler.postDelayed(this, 2000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        themedContext = ContextThemeWrapper(this, R.style.Theme_FloatBoxAI)

        createNotificationChannel()
        startForegroundCompat()

        if (Settings.canDrawOverlays(this)) {
            setupBubble()
            setupFloatingWindow()
            showBubble()
            watchdogHandler.postDelayed(bubbleWatchdog, 2000)
        } else {
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OPEN_WINDOW -> {
                isCapturingScreen = false
                if (windowView != null) openFloatingWindow()
            }
            ACTION_SHOW_BUBBLE -> {
                isCapturingScreen = false
                if (isWindowOpen) {
                    minimizeFloatingWindow()
                } else {
                    showBubble()
                }
            }
            ACTION_STOP -> {
                stopSelf()
            }
            ACTION_UPDATE_BUBBLE_SIZE, ACTION_UPDATE_BUBBLE_STYLE -> {
                applyBubbleStyle()
            }
            ACTION_OCR_IMAGE_SELECTED -> {
                val uri = intent.data
                if (uri != null) {
                    if (!isWindowOpen) openFloatingWindow()
                    switchTab(showBrowser = false)
                    runGalleryOcr(uri)
                }
            }
            ACTION_SCREEN_CAPTURED -> {
                isCapturingScreen = false
                openFloatingWindow()
                switchTab(showBrowser = false)
                loadCapturedScreenForCrop()
            }
            ACTION_SCREEN_CAPTURE_CANCELLED -> {
                isCapturingScreen = false
                openFloatingWindow()
                switchTab(showBrowser = false)
                Toast.makeText(this, "Scan layar dibatalkan.", Toast.LENGTH_SHORT).show()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        watchdogHandler.removeCallbacksAndMessages(null)
        try {
            if (::webView.isInitialized) {
                webView.stopLoading()
                webView.destroy()
            }
        } catch (_: Exception) {
        }
        removeOverlayView(bubbleView)
        removeOverlayView(windowView)
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifikasi layanan aktif FloatBox AI"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openPanelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingOverlayService::class.java).apply {
                action = ACTION_OPEN_WINDOW
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showBubbleIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, FloatingOverlayService::class.java).apply {
                action = ACTION_SHOW_BUBBLE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, FloatingOverlayService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_floatbox_logo)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_desc))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_action_open), openPanelIntent)
            .addAction(0, getString(R.string.notif_action_bubble), showBubbleIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubble() {
        val inflater = LayoutInflater.from(themedContext)
        val view = inflater.inflate(R.layout.layout_floating_bubble, null)
        bubbleView = view

        val (screenW, screenH) = getScreenSize()
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - dpToPx(64)
            y = screenH / 3
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        applyBubbleStyle()

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).roundToInt()
                    val dy = (event.rawY - initialTouchY).roundToInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val (w, h) = getScreenSize()
                        val bubbleSize = view.width.coerceAtLeast(dpToPx(48))
                        bubbleParams.x = (startX + dx).coerceIn(0, w - bubbleSize)
                        bubbleParams.y = (startY + dy).coerceIn(dpToPx(24), h - bubbleSize - dpToPx(24))
                        updateViewSafe(view, bubbleParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        openFloatingWindow()
                    } else {
                        snapBubbleToEdge(view)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun getSavedBubbleAlpha(): Float {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        return prefs.getFloat(MainActivity.KEY_BUBBLE_ALPHA, 0.85f).coerceIn(0.05f, 1.0f)
    }

    private fun applyBubbleStyle() {
        val view = bubbleView ?: return
        val ivBubble = view.findViewById<ImageView>(R.id.ivBubble)
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        val isSmall = prefs.getBoolean(MainActivity.KEY_BUBBLE_SMALL, true)
        val alpha = getSavedBubbleAlpha()

        val sizePx = dpToPx(if (isSmall) 46 else 58)
        val padPx = dpToPx(if (isSmall) 10 else 13)
        ivBubble.layoutParams = ivBubble.layoutParams.apply {
            width = sizePx
            height = sizePx
        }
        ivBubble.setPadding(padPx, padPx, padPx, padPx)
        ivBubble.requestLayout()

        view.alpha = alpha
    }

    private fun cycleBubbleOpacity() {
        val current = getSavedBubbleAlpha()
        val next = when {
            current > 0.75f -> 0.50f
            current > 0.35f -> 0.25f
            current > 0.15f -> 0.10f
            else -> 1.0f
        }
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putFloat(MainActivity.KEY_BUBBLE_ALPHA, next)
            .apply()
        applyBubbleStyle()
        val pct = (next * 100).roundToInt()
        val label = if (pct <= 15) "$pct% (Samar)" else "$pct%"
        Toast.makeText(this, "Transparansi ikon bubble: $label", Toast.LENGTH_SHORT).show()
    }

    private fun snapBubbleToEdge(view: View) {
        val (screenW, _) = getScreenSize()
        val bubbleWidth = view.width.coerceAtLeast(dpToPx(48))
        val targetX = if (bubbleParams.x + bubbleWidth / 2 < screenW / 2) {
            dpToPx(8)
        } else {
            screenW - bubbleWidth - dpToPx(8)
        }
        ValueAnimator.ofInt(bubbleParams.x, targetX).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                bubbleParams.x = animator.animatedValue as Int
                updateViewSafe(view, bubbleParams)
            }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    private fun setupFloatingWindow() {
        val inflater = LayoutInflater.from(themedContext)
        val view = inflater.inflate(R.layout.layout_floating_window, null)
        windowView = view

        val (screenW, screenH) = getScreenSize()
        savedWidth = (screenW * 0.88f).roundToInt().coerceAtMost(dpToPx(440))
        savedHeight = (screenH * 0.66f).roundToInt().coerceAtLeast(dpToPx(340))
        savedX = ((screenW - savedWidth) / 2).coerceAtLeast(0)
        savedY = ((screenH - savedHeight) / 4).coerceAtLeast(dpToPx(24))

        windowParams = WindowManager.LayoutParams(
            savedWidth,
            savedHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val headerBar = view.findViewById<LinearLayout>(R.id.headerBar)
        val btnQuickScan = view.findViewById<ImageButton>(R.id.btnQuickScan)
        val btnOpacity = view.findViewById<ImageButton>(R.id.btnOpacity)
        val btnMinimize = view.findViewById<ImageButton>(R.id.btnMinimize)
        btnMaximize = view.findViewById(R.id.btnMaximize)
        val btnClose = view.findViewById<ImageButton>(R.id.btnClose)

        tabBrowser = view.findViewById(R.id.tabBrowser)
        tabOcr = view.findViewById(R.id.tabOcr)
        ivTabBrowser = view.findViewById(R.id.ivTabBrowser)
        tvTabBrowser = view.findViewById(R.id.tvTabBrowser)
        ivTabOcr = view.findViewById(R.id.ivTabOcr)
        tvTabOcr = view.findViewById(R.id.tvTabOcr)
        panelBrowser = view.findViewById(R.id.panelBrowser)
        panelOcr = view.findViewById(R.id.panelOcr)

        val btnBack = view.findViewById<ImageButton>(R.id.btnBack)
        val btnForward = view.findViewById<ImageButton>(R.id.btnForward)
        val btnRefresh = view.findViewById<ImageButton>(R.id.btnRefresh)
        val btnHome = view.findViewById<ImageButton>(R.id.btnHome)
        etUrl = view.findViewById(R.id.etUrl)
        webProgressBar = view.findViewById(R.id.webProgressBar)
        webView = view.findViewById(R.id.webView)

        val btnScanScreen = view.findViewById<MaterialButton>(R.id.btnScanScreen)
        val btnPickImage = view.findViewById<MaterialButton>(R.id.btnPickImage)
        switchAutoSearch = view.findViewById(R.id.switchAutoSearch)
        cropImageView = view.findViewById(R.id.cropImageView)
        btnScanCrop = view.findViewById(R.id.btnScanCrop)
        val btnSearchOcr = view.findViewById<MaterialButton>(R.id.btnSearchOcr)
        val btnCopyOcr = view.findViewById<MaterialButton>(R.id.btnCopyOcr)
        ocrProgressBar = view.findViewById(R.id.ocrProgressBar)
        etOcrResult = view.findViewById(R.id.etOcrResult)

        val ivResizeHandle = view.findViewById<ImageView>(R.id.ivResizeHandle)

        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                setWindowFocusable(false)
            }
            false
        }

        var dragStartX = 0
        var dragStartY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        headerBar.setOnTouchListener { _, event ->
            setWindowFocusable(false)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = windowParams.x
                    dragStartY = windowParams.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val (w, h) = getScreenSize()
                    val newX = dragStartX + (event.rawX - touchStartX).roundToInt()
                    val newY = dragStartY + (event.rawY - touchStartY).roundToInt()
                    windowParams.x = newX.coerceIn(-windowParams.width / 2, w - dpToPx(80))
                    windowParams.y = newY.coerceIn(0, h - dpToPx(80))
                    updateViewSafe(view, windowParams)
                    true
                }
                else -> false
            }
        }

        var resizeStartW = 0
        var resizeStartH = 0
        var resizeTouchX = 0f
        var resizeTouchY = 0f
        ivResizeHandle.setOnTouchListener { _, event ->
            setWindowFocusable(false)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isMaximized = false
                    btnMaximize.setImageResource(R.drawable.ic_maximize)
                    resizeStartW = windowParams.width
                    resizeStartH = windowParams.height
                    resizeTouchX = event.rawX
                    resizeTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val (w, h) = getScreenSize()
                    val newW = (resizeStartW + (event.rawX - resizeTouchX).roundToInt())
                        .coerceIn(dpToPx(260), w - dpToPx(12))
                    val newH = (resizeStartH + (event.rawY - resizeTouchY).roundToInt())
                        .coerceIn(dpToPx(280), h - dpToPx(64))
                    windowParams.width = newW
                    windowParams.height = newH
                    savedWidth = newW
                    savedHeight = newH
                    updateViewSafe(view, windowParams)
                    true
                }
                else -> false
            }
        }

        btnQuickScan.setOnClickListener {
            startScreenScanFlow()
        }
        btnOpacity.setOnClickListener {
            cycleBubbleOpacity()
        }
        btnMinimize.setOnClickListener {
            minimizeFloatingWindow()
        }
        btnMaximize.setOnClickListener {
            toggleMaximize()
        }
        btnClose.setOnClickListener {
            stopSelf()
        }

        tabBrowser.setOnClickListener {
            switchTab(showBrowser = true)
        }
        tabOcr.setOnClickListener {
            switchTab(showBrowser = false)
        }

        setupWebView()

        etUrl.setOnTouchListener { _, _ ->
            setWindowFocusable(true)
            false
        }
        etUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                loadUrlOrSearch(etUrl.text.toString())
                hideKeyboard(etUrl)
                setWindowFocusable(false)
                true
            } else {
                false
            }
        }

        btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        btnForward.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        btnRefresh.setOnClickListener {
            webView.reload()
        }
        btnHome.setOnClickListener {
            loadUrlOrSearch(HOME_URL)
        }

        etOcrResult.setOnTouchListener { _, _ ->
            setWindowFocusable(true)
            false
        }

        btnScanScreen.setOnClickListener {
            startScreenScanFlow()
        }

        btnScanCrop.setOnClickListener {
            val cropped = cropImageView.getCroppedBitmap()
            if (cropped != null) {
                runBitmapOcr(cropped, triggerAutoSearch = switchAutoSearch.isChecked)
            } else {
                Toast.makeText(this, "Belum ada gambar untuk dipindai.", Toast.LENGTH_SHORT).show()
            }
        }

        btnPickImage.setOnClickListener {
            setWindowFocusable(false)
            val pickerIntent = Intent(this, ImagePickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(pickerIntent)
        }

        btnSearchOcr.setOnClickListener {
            val text = etOcrResult.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                Toast.makeText(this, "Belum ada teks untuk dicari.", Toast.LENGTH_SHORT).show()
            } else {
                searchInMiniBrowser(text)
            }
        }

        btnCopyOcr.setOnClickListener {
            val text = etOcrResult.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                Toast.makeText(this, "Belum ada teks untuk disalin.", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("FloatBox OCR", text))
                Toast.makeText(this, getString(R.string.text_copied), Toast.LENGTH_SHORT).show()
            }
        }

        loadUrlOrSearch(HOME_URL)
    }

    private fun startScreenScanFlow() {
        isCapturingScreen = true
        setWindowFocusable(false)
        removeOverlayView(windowView)
        removeOverlayView(bubbleView)
        isWindowOpen = false

        watchdogHandler.postDelayed({
            val intent = Intent(this, ScreenCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }, 120)
    }

    private fun loadCapturedScreenForCrop() {
        val file = File(cacheDir, ScreenCaptureService.SCAN_FILE_NAME)
        if (!file.exists()) {
            Toast.makeText(this, "Tangkapan layar tidak ditemukan.", Toast.LENGTH_SHORT).show()
            return
        }

        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap == null) {
            Toast.makeText(this, "Gagal memuat tangkapan layar.", Toast.LENGTH_SHORT).show()
            return
        }

        cropImageView.visibility = View.VISIBLE
        btnScanCrop.visibility = View.VISIBLE
        cropImageView.setImageBitmap(bitmap)
        etOcrResult.setText("")
        etOcrResult.hint = getString(R.string.ocr_crop_ready)
    }

    private fun searchInMiniBrowser(rawText: String) {
        val cleanQuery = rawText
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(300)
        if (cleanQuery.isEmpty()) return

        val searchUrl = "https://www.google.com/search?q=${Uri.encode(cleanQuery)}"
        switchTab(showBrowser = true)
        etUrl.setText(searchUrl)
        webView.loadUrl(searchUrl)
    }

    private fun runBitmapOcr(bitmap: Bitmap, triggerAutoSearch: Boolean) {
        ocrProgressBar.visibility = View.VISIBLE
        etOcrResult.setText(getString(R.string.ocr_processing))

        OcrHelper.recognizeTextFromBitmap(
            bitmap = bitmap,
            onSuccess = { text ->
                windowView?.post {
                    ocrProgressBar.visibility = View.GONE
                    if (text.isBlank()) {
                        etOcrResult.setText("")
                        etOcrResult.hint = getString(R.string.ocr_empty)
                        Toast.makeText(this, getString(R.string.ocr_empty), Toast.LENGTH_SHORT).show()
                    } else {
                        etOcrResult.setText(text)
                        if (triggerAutoSearch) {
                            searchInMiniBrowser(text)
                        }
                    }
                }
            },
            onError = { errorMsg ->
                windowView?.post {
                    ocrProgressBar.visibility = View.GONE
                    etOcrResult.setText("")
                    Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun runGalleryOcr(imageUri: Uri) {
        ocrProgressBar.visibility = View.VISIBLE
        etOcrResult.setText(getString(R.string.ocr_processing))

        OcrHelper.recognizeTextFromUri(
            context = this,
            imageUri = imageUri,
            onPreviewReady = { bitmap ->
                windowView?.post {
                    if (bitmap != null) {
                        cropImageView.visibility = View.VISIBLE
                        btnScanCrop.visibility = View.VISIBLE
                        cropImageView.setImageBitmap(bitmap)
                    }
                }
            },
            onSuccess = { text ->
                windowView?.post {
                    ocrProgressBar.visibility = View.GONE
                    if (text.isBlank()) {
                        etOcrResult.setText("")
                        etOcrResult.hint = getString(R.string.ocr_empty)
                    } else {
                        etOcrResult.setText(text)
                        if (switchAutoSearch.isChecked) {
                            searchInMiniBrowser(text)
                        }
                    }
                }
            },
            onError = { errorMsg ->
                windowView?.post {
                    ocrProgressBar.visibility = View.GONE
                    etOcrResult.setText("")
                    Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        webView.setOnTouchListener { _, _ ->
            setWindowFocusable(true)
            false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return !(url.startsWith("http://") || url.startsWith("https://"))
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrBlank() && !etUrl.hasFocus()) {
                    etUrl.setText(url)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                webProgressBar.progress = newProgress
                webProgressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }
    }

    private fun loadUrlOrSearch(rawInput: String) {
        val input = rawInput.trim()
        if (input.isEmpty()) return

        val targetUrl = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            !input.contains(" ") && input.contains(".") -> "https://$input"
            else -> "https://www.google.com/search?q=${Uri.encode(input)}"
        }
        etUrl.setText(targetUrl)
        webView.loadUrl(targetUrl)
    }

    private fun switchTab(showBrowser: Boolean) {
        val white = ContextCompat.getColor(this, R.color.white)
        val navy = ContextCompat.getColor(this, R.color.navy_primary)

        if (showBrowser) {
            panelBrowser.visibility = View.VISIBLE
            panelOcr.visibility = View.GONE

            tabBrowser.setBackgroundResource(R.drawable.bg_tab_active)
            tvTabBrowser.setTextColor(white)
            ivTabBrowser.setColorFilter(white)

            tabOcr.setBackgroundResource(R.drawable.bg_tab_inactive)
            tvTabOcr.setTextColor(navy)
            ivTabOcr.setColorFilter(navy)
        } else {
            panelBrowser.visibility = View.GONE
            panelOcr.visibility = View.VISIBLE

            tabOcr.setBackgroundResource(R.drawable.bg_tab_active)
            tvTabOcr.setTextColor(white)
            ivTabOcr.setColorFilter(white)

            tabBrowser.setBackgroundResource(R.drawable.bg_tab_inactive)
            tvTabBrowser.setTextColor(navy)
            ivTabBrowser.setColorFilter(navy)
        }
    }

    private fun toggleMaximize() {
        val view = windowView ?: return
        val (screenW, screenH) = getScreenSize()
        if (!isMaximized) {
            savedWidth = windowParams.width
            savedHeight = windowParams.height
            savedX = windowParams.x
            savedY = windowParams.y

            windowParams.width = (screenW * 0.96f).roundToInt()
            windowParams.height = (screenH * 0.86f).roundToInt()
            windowParams.x = ((screenW - windowParams.width) / 2)
            windowParams.y = dpToPx(24)
            btnMaximize.setImageResource(R.drawable.ic_restore)
            isMaximized = true
        } else {
            windowParams.width = savedWidth
            windowParams.height = savedHeight
            windowParams.x = savedX
            windowParams.y = savedY
            btnMaximize.setImageResource(R.drawable.ic_maximize)
            isMaximized = false
        }
        updateViewSafe(view, windowParams)
    }

    private fun showBubble() {
        val bubble = bubbleView ?: return
        applyBubbleStyle()
        val targetAlpha = getSavedBubbleAlpha()
        if (bubble.parent == null) {
            try {
                windowManager.addView(bubble, bubbleParams)
            } catch (_: Exception) {
                return
            }
        }
        bubble.visibility = View.VISIBLE
        bubble.scaleX = 0.7f
        bubble.scaleY = 0.7f
        bubble.alpha = 0f
        bubble.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(targetAlpha)
            .setDuration(180)
            .start()
    }

    private fun openFloatingWindow() {
        if (isWindowOpen) return
        removeOverlayView(bubbleView)

        val win = windowView ?: return
        if (win.parent == null) {
            try {
                windowManager.addView(win, windowParams)
            } catch (_: Exception) {
                return
            }
        }
        win.scaleX = 0.85f
        win.scaleY = 0.85f
        win.alpha = 0f
        win.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()

        isWindowOpen = true
    }

    private fun minimizeFloatingWindow() {
        if (!isWindowOpen) return
        setWindowFocusable(false)
        val win = windowView ?: return
        win.animate()
            .scaleX(0.85f)
            .scaleY(0.85f)
            .alpha(0f)
            .setDuration(160)
            .withEndAction {
                removeOverlayView(win)
                isWindowOpen = false
                showBubble()
            }
            .start()
    }

    private fun setWindowFocusable(focusable: Boolean) {
        val win = windowView ?: return
        if (isWindowFocusable == focusable) return
        isWindowFocusable = focusable

        if (focusable) {
            windowParams.flags = windowParams.flags and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            windowParams.flags = windowParams.flags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        updateViewSafe(win, windowParams)
    }

    private fun hideKeyboard(target: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(target.windowToken, 0)
        target.clearFocus()
    }

    private fun updateViewSafe(view: View, params: WindowManager.LayoutParams) {
        if (view.parent != null) {
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
        }
    }

    private fun removeOverlayView(view: View?) {
        if (view != null && view.parent != null) {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).roundToInt()

    companion object {
        @Volatile
        var isRunning = false
            private set

        private const val CHANNEL_ID = "floatbox_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val HOME_URL = "https://www.google.com"

        const val ACTION_START = "com.floatbox.ai.ACTION_START"
        const val ACTION_STOP = "com.floatbox.ai.ACTION_STOP"
        const val ACTION_OPEN_WINDOW = "com.floatbox.ai.ACTION_OPEN_WINDOW"
        const val ACTION_SHOW_BUBBLE = "com.floatbox.ai.ACTION_SHOW_BUBBLE"
        const val ACTION_UPDATE_BUBBLE_SIZE = "com.floatbox.ai.ACTION_UPDATE_BUBBLE_SIZE"
        const val ACTION_UPDATE_BUBBLE_STYLE = "com.floatbox.ai.ACTION_UPDATE_BUBBLE_STYLE"
        const val ACTION_OCR_IMAGE_SELECTED = "com.floatbox.ai.ACTION_OCR_IMAGE_SELECTED"
        const val ACTION_SCREEN_CAPTURED = "com.floatbox.ai.ACTION_SCREEN_CAPTURED"
        const val ACTION_SCREEN_CAPTURE_CANCELLED = "com.floatbox.ai.ACTION_SCREEN_CAPTURE_CANCELLED"
    }
}