package tube.three06.app

import android.app.Application
import tube.three06.app.di.AppGraph

class App : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.create(this)
    }
}
