package ch.sommermeyer.rubato

import android.app.Application
import android.content.Context

class RubatoApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as RubatoApp).graph
