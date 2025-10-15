package com.example.scenic_navigation

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.example.scenic_navigation.ui.RouteFragment
import com.example.scenic_navigation.ui.RecommendationsFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.osmdroid.config.Configuration

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        // Show RouteFragment by default
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, RouteFragment())
            }
        }

        // Setup bottom navigation
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_route -> {
                    supportFragmentManager.commit {
                        replace(R.id.fragment_container, RouteFragment())
                        // Removed addToBackStack(null) for tab navigation
                    }
                    true
                }
                R.id.nav_recommendations -> {
                    supportFragmentManager.commit {
                        replace(R.id.fragment_container, RecommendationsFragment())
                        // Removed addToBackStack(null) for tab navigation
                    }
                    true
                }
                else -> false
            }
        }
    }
}
