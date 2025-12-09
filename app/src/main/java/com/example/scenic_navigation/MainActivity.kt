package com.example.scenic_navigation

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.example.scenic_navigation.ui.RouteFragment
import com.example.scenic_navigation.ui.RecommendationsFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.osmdroid.config.Configuration
import android.view.Menu
import android.view.MenuItem
import android.content.Intent
import com.google.android.material.appbar.MaterialToolbar

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        // Setup toolbar as ActionBar for consistent Material AppBar behavior
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)
        // Initialize FavoriteStore
        com.example.scenic_navigation.FavoriteStore.init(this)

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
                R.id.nav_favorites -> {
                    supportFragmentManager.commit {
                        replace(R.id.fragment_container, com.example.scenic_navigation.ui.FavoritesFragment())
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(com.example.scenic_navigation.R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            com.example.scenic_navigation.R.id.action_settings -> {
                val intent = Intent(this, com.example.scenic_navigation.ui.SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
