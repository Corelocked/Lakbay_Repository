package com.example.scenic_navigation

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commit
import com.example.scenic_navigation.ui.FavoritesFragment
import com.example.scenic_navigation.ui.LoginActivity
import com.example.scenic_navigation.ui.RouteFragment
import com.example.scenic_navigation.ui.RecommendationsFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import org.osmdroid.config.Configuration
import java.io.BufferedReader

// new imports
import com.google.android.material.card.MaterialCardView
import android.widget.TextView
import androidx.activity.viewModels
import com.example.scenic_navigation.viewmodel.RouteViewModel
import android.view.View

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    private var legendCard: MaterialCardView? = null
    private var tvOverlayDistance: TextView? = null
    private var tvOverlayEta: TextView? = null

    private val routeViewModel: RouteViewModel by viewModels()

    // Track whether a route has been calculated (non-zero distance/duration)
    private var routeHasData: Boolean = false

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

        // find legend card and inner textviews
        legendCard = findViewById(R.id.legend_overlay_card)
        tvOverlayDistance = findViewById(R.id.tv_overlay_distance)
        tvOverlayEta = findViewById(R.id.tv_overlay_eta)

        // Observe route summary LiveData to update the overlay texts
        routeViewModel.routeDistanceMeters.observe(this) { meters ->
            try {
                if (meters != null && meters > 0.0) {
                    val km = meters / 1000.0
                    val text = if (km >= 1.0) String.format(java.util.Locale.getDefault(), "%.1f km", km) else String.format(java.util.Locale.getDefault(), "%d m", meters.toInt())
                    tvOverlayDistance?.text = getString(R.string.overlay_distance_placeholder).replace("—", text)
                    routeHasData = true
                } else {
                    tvOverlayDistance?.text = getString(R.string.overlay_distance_placeholder)
                }
            } catch (_: Exception) { }
            updateLegendVisibility()
        }

        routeViewModel.routeDurationSeconds.observe(this) { secs ->
            try {
                if (secs != null && secs > 0L) {
                    val hours = secs / 3600
                    val mins = (secs % 3600) / 60
                    val text = if (hours > 0) String.format(java.util.Locale.getDefault(), "%dh %02dm", hours, mins) else String.format(java.util.Locale.getDefault(), "%dm", mins)
                    tvOverlayEta?.text = getString(R.string.overlay_eta_placeholder).replace("—", text)
                    routeHasData = true
                } else {
                    tvOverlayEta?.text = getString(R.string.overlay_eta_placeholder)
                }
            } catch (_: Exception) { }
            updateLegendVisibility()
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
                    // hide legend when route is active
                    updateLegendVisibility()
                    invalidateOptionsMenu()
                    true
                }
                R.id.nav_recommendations -> {
                    supportFragmentManager.commit {
                        replace(R.id.fragment_container, RecommendationsFragment())
                    }
                    // show legend on other tabs
                    updateLegendVisibility()
                    // when showing RecommendationsFragment we don't want the three-dot overflow
                    invalidateOptionsMenu()
                    true
                }
                R.id.nav_favorites -> {
                    supportFragmentManager.commit {
                        replace(R.id.fragment_container, FavoritesFragment())
                    }
                    updateLegendVisibility()
                    invalidateOptionsMenu()
                    true
                }
                R.id.nav_sign_out -> {
                    // Confirm before signing out
                    AlertDialog.Builder(this)
                        .setTitle("Sign out")
                        .setMessage("Are you sure you want to sign out?")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            auth.signOut()
                            val intent = Intent(this, LoginActivity::class.java)
                            startActivity(intent)
                            finish()
                        }
                        .show()
                    true
                }
                else -> false
            }
        }

        // ensure legend visibility matches initial fragment
        updateLegendVisibility()
    }

    private fun updateLegendVisibility() {
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        val isRoute = current is RouteFragment
        // Show overlay only when on RouteFragment and route data exists
        legendCard?.visibility = if (isRoute && routeHasData) View.VISIBLE else View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        // Disable the overflow menu globally so no three-dot menu appears beside the logo
        // Returning false prevents any menu from being displayed.
        return false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_model_info -> {
                showModelInfoDialog()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, com.example.scenic_navigation.ui.SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showModelInfoDialog() {
        try {
            val stream = assets.open("models/model_metadata.json")
            val content = stream.bufferedReader().use(BufferedReader::readText)
            val pretty = try {
                val json = org.json.JSONObject(content)
                json.toString(2)
            } catch (_: Exception) {
                content
            }
            AlertDialog.Builder(this)
                .setTitle("Model metadata")
                .setMessage(pretty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (_: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Model metadata")
                .setMessage("No metadata available")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

}
