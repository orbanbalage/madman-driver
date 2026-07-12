package com.madmandriver

import android.app.Application

class MadmanDriverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
