package br.com.centralautobrasil

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainDashboardScreen(carContext: CarContext) : Screen(carContext) {

    init {
        RadioPlayerController.onStateChanged = {
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val gridBuilder = ItemList.Builder()

        val activeStation = RadioPlayerController.currentStation
        val radioSubtitle = if (RadioPlayerController.isPlaying() && activeStation != null) {
            "Tocando: ${activeStation.name}"
        } else {
            "FM / AM / Web Rádios ao vivo"
        }

        gridBuilder.addItem(
            GridItem.Builder()
                .setTitle("Rádios do Brasil")
                .setText(radioSubtitle)
                .setImage(CarIcon.APP_ICON)
                .setOnClickListener {
                    screenManager.push(BrazilRadiosCarScreen(carContext))
                }
                .build()
        )

        gridBuilder.addItem(
            GridItem.Builder()
                .setTitle("YouTube & TV Ao Vivo")
                .setText("Canais, Músicas e Streams")
                .setImage(CarIcon.APP_ICON)
                .setOnClickListener {
                    screenManager.push(YouTubeCarScreen(carContext))
                }
                .build()
        )

        gridBuilder.addItem(
            GridItem.Builder()
                .setTitle(if (RadioPlayerController.isPlaying()) "Pausar Rádio" else "Tocar Última Rádio")
                .setText(activeStation?.name ?: "Início rápido")
                .setImage(CarIcon.APP_ICON)
                .setOnClickListener {
                    RadioPlayerController.togglePlayPause(carContext)
                    invalidate()
                }
                .build()
        )

        val homeAddr = AppRepository.getHomeAddress(carContext)
        gridBuilder.addItem(
            GridItem.Builder()
                .setTitle("Rota para Casa")
                .setText(homeAddr)
                .setImage(CarIcon.APP_ICON)
                .setOnClickListener {
                    val navIntent = Intent(
                        CarContext.ACTION_NAVIGATE,
                        Uri.parse("geo:0,0?q=${Uri.encode(homeAddr)}")
                    )
                    carContext.startCarApp(navIntent)
                }
                .build()
        )

        gridBuilder.addItem(
            GridItem.Builder()
                .setTitle("Portão / Casa Smart")
                .setText("Acionar Webhook IoT")
                .setImage(CarIcon.APP_ICON)
                .setOnClickListener {
                    triggerSmartHomeWebhook()
                }
                .build()
        )

        return GridTemplate.Builder()
            .setTitle("Central Auto Brasil")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(gridBuilder.build())
            .build()
    }

    private fun triggerSmartHomeWebhook() {
        val urlStr = AppRepository.getWebhookUrl(carContext)
        if (urlStr.isBlank()) {
            CarToast.makeText(
                carContext,
                "Configure a URL do Portão/Webhook no app do celular.",
                CarToast.LENGTH_LONG
            ).show()
            return
        }

        CarToast.makeText(carContext, "Enviando comando para o portão...", CarToast.LENGTH_SHORT).show()
        thread {
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                val code = conn.responseCode
                conn.disconnect()
                carContext.mainExecutor.execute {
                    CarToast.makeText(
                        carContext,
                        "Comando executado (Status HTTP $code)",
                        CarToast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                carContext.mainExecutor.execute {
                    CarToast.makeText(
                        carContext,
                        "Erro ao acionar webhook: ${e.message}",
                        CarToast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
