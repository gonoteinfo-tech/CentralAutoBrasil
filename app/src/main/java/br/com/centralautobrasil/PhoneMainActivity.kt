package br.com.centralautobrasil

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class PhoneMainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_YOUTUBE_URL = "extra_youtube_url"
    }

    private lateinit var youtubeWebView: WebView
    private lateinit var statusBanner: TextView

    class BackgroundAudioWebView(context: android.content.Context) : WebView(context) {
        override fun onWindowVisibilityChanged(visibility: Int) {
            if (visibility != View.GONE && visibility != View.INVISIBLE) {
                super.onWindowVisibilityChanged(visibility)
            } else {
                super.onWindowVisibilityChanged(View.VISIBLE)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF0A192F.toInt())
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 20)
            setBackgroundColor(0xFF112240.toInt())
        }

        val titleView = TextView(this).apply {
            text = "🇧🇷 Central Auto Brasil • YouTube & Rádios"
            textSize = 18f
            setTextColor(0xFFFEDD00.toInt())
        }

        statusBanner = TextView(this).apply {
            text = "Pronto para transmitir para a multimídia via Android Auto"
            textSize = 13f
            setTextColor(0xFFCCD6F6.toInt())
        }

        headerLayout.addView(titleView)
        headerLayout.addView(statusBanner)
        rootLayout.addView(headerLayout)

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 12, 16, 12)
            gravity = Gravity.CENTER
        }

        val btnRadios = Button(this).apply {
            text = "📻 Rádios BR"
            setOnClickListener { showRadiosDialog() }
        }

        val btnAddRadio = Button(this).apply {
            text = "+ Nova Rádio"
            setOnClickListener { showAddRadioDialog() }
        }

        val btnAddYouTube = Button(this).apply {
            text = "+ Atalho YT"
            setOnClickListener { showAddYouTubeDialog() }
        }

        val btnConfig = Button(this).apply {
            text = "⚙️ Casa/Portão"
            setOnClickListener { showSettingsDialog() }
        }

        buttonBar.addView(btnRadios)
        buttonBar.addView(btnAddRadio)
        buttonBar.addView(btnAddYouTube)
        buttonBar.addView(btnConfig)
        rootLayout.addView(buttonBar)

        youtubeWebView = BackgroundAudioWebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    return false
                }
            }
        }

        rootLayout.addView(youtubeWebView)
        setContentView(rootLayout)

        val initialUrl = intent?.getStringExtra(EXTRA_YOUTUBE_URL) ?: "https://m.youtube.com"
        youtubeWebView.loadUrl(initialUrl)

        RadioPlayerController.onStateChanged = {
            runOnUiThread {
                val st = RadioPlayerController.currentStation
                if (RadioPlayerController.isPlaying() && st != null) {
                    statusBanner.text = "🔊 Tocando Rádio: ${st.name} (${st.region})"
                } else {
                    statusBanner.text = "Player YouTube Ativo • Áudio liberado em segundo plano"
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newUrl = intent.getStringExtra(EXTRA_YOUTUBE_URL)
        if (!newUrl.isNullOrBlank()) {
            youtubeWebView.loadUrl(newUrl)
            statusBanner.text = "▶ Reproduzindo YouTube selecionado na multimídia"
        }
    }

    private fun showRadiosDialog() {
        val radios = AppRepository.getRadios(this)
        val names = radios.map { "${it.name} (${it.region})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Sintonizar Rádio do Brasil")
            .setItems(names) { _, which ->
                val chosen = radios[which]
                startService(Intent(this, RadioPlaybackService::class.java))
                RadioPlayerController.playStation(this, chosen)
                Toast.makeText(this, "Tocando ${chosen.name}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Parar Rádio") { _, _ ->
                RadioPlayerController.stop()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showAddRadioDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }
        val inputName = EditText(this).apply { hint = "Nome da Rádio (ex: 99,5 FM Goiás)" }
        val inputRegion = EditText(this).apply { hint = "Cidade / Estado (ex: Anápolis - GO)" }
        val inputUrl = EditText(this).apply { hint = "URL do Stream (.mp3 / .aac / .m3u8)" }

        layout.addView(inputName)
        layout.addView(inputRegion)
        layout.addView(inputUrl)

        AlertDialog.Builder(this)
            .setTitle("Adicionar Rádio Brasileira")
            .setView(layout)
            .setPositiveButton("Salvar no Carro") { _, _ ->
                val name = inputName.text.toString().trim()
                val region = inputRegion.text.toString().trim().ifEmpty { "Brasil" }
                val url = inputUrl.text.toString().trim()
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    AppRepository.addCustomRadio(this, name, region, url)
                    Toast.makeText(this, "Rádio adicionada à multimídia!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showAddYouTubeDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }
        val inputTitle = EditText(this).apply { hint = "Título do Canal ou Playlist" }
        val inputUrl = EditText(this).apply {
            hint = "URL do YouTube"
            setText(youtubeWebView.url ?: "https://m.youtube.com")
        }

        layout.addView(inputTitle)
        layout.addView(inputUrl)

        AlertDialog.Builder(this)
            .setTitle("Salvar Página Atual do YouTube no Carro")
            .setView(layout)
            .setPositiveButton("Salvar") { _, _ ->
                val title = inputTitle.text.toString().trim().ifEmpty { "Favorito do YouTube" }
                val url = inputUrl.text.toString().trim()
                if (url.isNotEmpty()) {
                    AppRepository.addYouTubeItem(this, title, url)
                    Toast.makeText(this, "Atalho salvo na tela do Android Auto!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showSettingsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 12)
        }
        val inputHome = EditText(this).apply {
            hint = "Endereço de Casa (para GPS Rápido)"
            setText(AppRepository.getHomeAddress(this@PhoneMainActivity))
        }
        val inputWebhook = EditText(this).apply {
            hint = "URL Webhook do Portão / Casa Inteligente"
            setText(AppRepository.getWebhookUrl(this@PhoneMainActivity))
        }

        layout.addView(inputHome)
        layout.addView(inputWebhook)

        AlertDialog.Builder(this)
            .setTitle("Configurar Funções do Carro")
            .setView(layout)
            .setPositiveButton("Salvar") { _, _ ->
                AppRepository.setHomeAddress(this, inputHome.text.toString().trim())
                AppRepository.setWebhookUrl(this, inputWebhook.text.toString().trim())
                Toast.makeText(this, "Configurações atualizadas!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onBackPressed() {
        if (youtubeWebView.canGoBack()) {
            youtubeWebView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
