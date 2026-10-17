package br.com.centralautobrasil

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.*

class BrazilRadiosCarScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
        val stations = AppRepository.getRadios(carContext)
        val current = RadioPlayerController.currentStation

        stations.forEach { station ->
            val isPlayingThis = (current?.id == station.id && RadioPlayerController.isPlaying())
            val statusPrefix = if (isPlayingThis) "🔊 TOCANDO AGORA • " else ""

            listBuilder.addItem(
                Row.Builder()
                    .setTitle(station.name)
                    .addText("$statusPrefix${station.region} • ${station.category}")
                    .setOnClickListener {
                        val serviceIntent = Intent(carContext, RadioPlaybackService::class.java)
                        carContext.startService(serviceIntent)

                        RadioPlayerController.playStation(carContext, station)
                        CarToast.makeText(
                            carContext,
                            "Sintonizando ${station.name}...",
                            CarToast.LENGTH_SHORT
                        ).show()
                        invalidate()
                    }
                    .build()
            )
        }

        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(if (RadioPlayerController.isPlaying()) "Pausar" else "Play")
                    .setOnClickListener {
                        RadioPlayerController.togglePlayPause(carContext)
                        invalidate()
                    }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setTitle("Rádios do Brasil Ao Vivo")
            .setHeaderAction(Action.BACK)
            .setActionStrip(actionStrip)
            .setSingleList(listBuilder.build())
            .build()
    }
}
