package br.com.centralautobrasil

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.*

class YouTubeCarScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
        val ytItems = AppRepository.getYouTubeItems(carContext)

        ytItems.forEach { item ->
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(item.title)
                    .addText(item.subtitle)
                    .setOnClickListener {
                        RadioPlayerController.stop()

                        val intent = Intent(carContext, PhoneMainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra(PhoneMainActivity.EXTRA_YOUTUBE_URL, item.url)
                        }
                        carContext.startActivity(intent)

                        CarToast.makeText(
                            carContext,
                            "Abrindo no Player YouTube: ${item.title}",
                            CarToast.LENGTH_LONG
                        ).show()
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setTitle("YouTube & Canais Ao Vivo")
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .build()
    }
}
