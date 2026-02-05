package com.example.scenic_navigation

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commit
import com.example.scenic_navigation.ui.FavoritesFragment
import com.example.scenic_navigation.ui.LoginActivity
import com.example.scenic_navigation.ui.RouteFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import org.osmdroid.config.Configuration

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    override fun attachBaseContext(newBase: android.content.Context) {
        val language = com.example.scenic_navigation.utils.LocaleHelper.getLanguage(newBase)
        val context = com.example.scenic_navigation.utils.LocaleHelper.setLocale(newBase, language)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()

        // Require user sign-in: if no user is signed in, send them to the LoginActivity
        if (auth.currentUser == null) {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        FavoriteStore.init(this)

        // Setup toolbar as ActionBar for consistent Material AppBar behavior
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply padding to the top of the toolbar to push it down
            view.setPadding(insets.left, insets.top, insets.right, 0)

            // Return the insets so other views can consume them if needed
            WindowInsetsCompat.CONSUMED
        }

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
                R.id.nav_favorites -> {
                    supportFragmentManager.commit {
                        replace(R.id.fragment_container, FavoritesFragment())
                    }
                    true
                }
                R.id.nav_sign_out -> {
                    auth.signOut()
                    val intent = Intent(this, LoginActivity::class.java)
                    startActivity(intent)
                    finish()
                    true
                }
                else -> false
            }
        }
    }

}
