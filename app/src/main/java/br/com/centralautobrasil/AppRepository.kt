package br.com.centralautobrasil

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RadioStation(
    val id: String,
    val name: String,
    val region: String,
    val category: String,
    val streamUrl: String
)

data class YouTubeChannelItem(
    val title: String,
    val subtitle: String,
    val url: String
)

object AppRepository {
    private const val PREFS_NAME = "central_auto_brasil_prefs"
    private const val KEY_CUSTOM_RADIOS = "custom_radios"
    private const val KEY_YOUTUBE_ITEMS = "youtube_items"
    private const val KEY_WEBHOOK_URL = "webhook_url"
    private const val KEY_HOME_ADDRESS = "home_address"

    private val defaultBrazilianRadios = listOf(
        RadioStation(
            id = "antena1",
            name = "Antena 1 FM 94.7",
            region = "Nacional / São Paulo",
            category = "Adulto Contemporâneo / Flashback",
            streamUrl = "https://antenaone.crossradio.com.br/stream/1;"
        ),
        RadioStation(
            id = "jovempan",
            name = "Jovem Pan FM 100.9",
            region = "Nacional / São Paulo",
            category = "Pop / Notícias",
            streamUrl = "https://stream-152.zeno.fm/c45wbq2us3buv"
        ),
        RadioStation(
            id = "bandnews",
            name = "BandNews FM",
            region = "Nacional / Brasília & SP",
            category = "Jornalismo 24h",
            streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/BANDNEWSFM_SPAAC.aac"
        ),
        RadioStation(
            id = "cbn_sp",
            name = "Rádio CBN",
            region = "Nacional",
            category = "Notícias & Esportes",
            streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/CBN_SPAAC.aac"
        ),
        RadioStation(
            id = "alphafm",
            name = "Alpha FM 101.7",
            region = "São Paulo / Goiás",
            category = "MPB & Internacional",
            streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/RADIO_ALPHAFMAAC.aac"
        ),
        RadioStation(
            id = "kissfm",
            name = "Kiss FM 92.5",
            region = "São Paulo / Centro-Oeste",
            category = "Classic Rock",
            streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/RADIO_KISSFMAAC.aac"
        ),
        RadioStation(
            id = "itatiaia",
            name = "Rádio Itatiaia 95.7 FM",
            region = "Minas Gerais",
            category = "Esportes & Jornalismo",
            streamUrl = "https://8903.brasilstream.com.br/stream"
        ),
        RadioStation(
            id = "sertanejo_br",
            name = "Rádio Sertanejo Brasil FM",
            region = "Goiás / Interior",
            category = "Sertanejo Universitário & Raiz",
            streamUrl = "https://stream.zeno.fm/0r0xa792kwzuv"
        )
    )

    private val defaultYouTubeItems = listOf(
        YouTubeChannelItem(
            title = "YouTube Início (Navegação Completa)",
            subtitle = "Abrir interface principal do YouTube",
            url = "https://m.youtube.com"
        ),
        YouTubeChannelItem(
            title = "TV Ao Vivo no YouTube (Jovem Pan / CNN / Record)",
            subtitle = "Canais brasileiros de notícias 24h ao vivo",
            url = "https://m.youtube.com/results?search_query=ao+vivo+noticias+brasil"
        ),
        YouTubeChannelItem(
            title = "CazéTV - Esportes Ao Vivo",
            subtitle = "Transmissões esportivas brasileiras",
            url = "https://m.youtube.com/@CazeTV/streams"
        ),
        YouTubeChannelItem(
            title = "Sertanejo & Modão para Estrada",
            subtitle = "Playlists longas para viagem",
            url = "https://m.youtube.com/results?search_query=playlist+sertanejo+estrada"
        ),
        YouTubeChannelItem(
            title = "Lo-Fi & Música Instrumental",
            subtitle = "Stream contínuo para dirigir",
            url = "https://m.youtube.com/results?search_query=lofi+hip+hop+radio+live"
        )
    )

    fun getRadios(context: Context): List<RadioStation> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CUSTOM_RADIOS, null) ?: return defaultBrazilianRadios
        val customList = mutableListOf<RadioStation>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                customList.add(
                    RadioStation(
                        id = obj.optString("id", "custom_$i"),
                        name = obj.getString("name"),
                        region = obj.optString("region", "Personalizada"),
                        category = obj.optString("category", "Rádio Customizada"),
                        streamUrl = obj.getString("streamUrl")
                    )
                )
            }
        } catch (_: Exception) {}
        return customList + defaultBrazilianRadios
    }

    fun addCustomRadio(context: Context, name: String, region: String, streamUrl: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentJson = prefs.getString(KEY_CUSTOM_RADIOS, "[]") ?: "[]"
        val arr = JSONArray(currentJson)
        val obj = JSONObject().apply {
            put("id", "custom_${System.currentTimeMillis()}")
            put("name", name)
            put("region", region)
            put("category", "Adicionada pelo Celular")
            put("streamUrl", streamUrl)
        }
        arr.put(obj)
        prefs.edit().putString(KEY_CUSTOM_RADIOS, arr.toString()).apply()
    }

    fun getYouTubeItems(context: Context): List<YouTubeChannelItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_YOUTUBE_ITEMS, null) ?: return defaultYouTubeItems
        val customList = mutableListOf<YouTubeChannelItem>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                customList.add(
                    YouTubeChannelItem(
                        title = obj.getString("title"),
                        subtitle = obj.optString("subtitle", "Favorito salvo no celular"),
                        url = obj.getString("url")
                    )
                )
            }
        } catch (_: Exception) {}
        return customList + defaultYouTubeItems
    }

    fun addYouTubeItem(context: Context, title: String, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentJson = prefs.getString(KEY_YOUTUBE_ITEMS, "[]") ?: "[]"
        val arr = JSONArray(currentJson)
        val obj = JSONObject().apply {
            put("title", title)
            put("subtitle", "Atalho personalizado")
            put("url", url)
        }
        arr.put(obj)
        prefs.edit().putString(KEY_YOUTUBE_ITEMS, arr.toString()).apply()
    }

    fun getHomeAddress(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_HOME_ADDRESS, "Anápolis, GO") ?: "Anápolis, GO"
    }

    fun setHomeAddress(context: Context, address: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_HOME_ADDRESS, address).apply()
    }

    fun getWebhookUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
    }

    fun setWebhookUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_WEBHOOK_URL, url).apply()
    }
}
