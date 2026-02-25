package com.example.scenic_navigation.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewStub
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.scenic_navigation.MainActivity
import com.example.scenic_navigation.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private val RC_SIGN_IN = 9001
    private var passwordVisible = false

    private enum class Mode { LOGIN, SIGNUP }
    private var currentMode = Mode.LOGIN

    // Inflated roots (null until inflated)
    private var loginRoot: View? = null
    private var signupRoot: View? = null

    companion object {
        private const val KEY_MODE = "key_mode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()

        // Restore mode if available
        currentMode = if (savedInstanceState?.getString(KEY_MODE) == Mode.SIGNUP.name) Mode.SIGNUP else Mode.LOGIN

        // Initialize GoogleSignInClient if possible (deferred until we bind views that need it)
        initGoogleSignInClientIfPossible()

        // Show initial mode
        showMode(currentMode)

        // Handle system back: when in SIGNUP, go back to LOGIN; otherwise use default dispatcher
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentMode == Mode.SIGNUP) {
                    showMode(Mode.LOGIN)
                } else {
                    // disable this callback and let the system handle the back
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun initGoogleSignInClientIfPossible() {
        val clientId = getString(R.string.default_web_client_id)
        if (clientId.isBlank() || clientId.contains("YOUR_DEFAULT_WEB_CLIENT_ID") || clientId.contains("YOUR_DEFAULT")) {
            // Will disable buttons when binding views
            return
        }

        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(clientId)
                .requestEmail()
                .build()
            googleSignInClient = GoogleSignIn.getClient(this, gso)
        } catch (e: Exception) {
            Log.w("LoginActivity", "Failed to initialize GoogleSignInClient: ${e.message}")
        }
    }

    private fun showMode(mode: Mode) {
        currentMode = mode

        when (mode) {
            Mode.LOGIN -> {
                // Inflate login if needed
                if (loginRoot == null) {
                    val stub = findViewById<ViewStub>(R.id.stub_login)
                    loginRoot = stub.inflate()
                    bindLoginViews(loginRoot!!)
                }
                // Ensure visibility
                loginRoot?.visibility = View.VISIBLE
                signupRoot?.visibility = View.GONE
            }
            Mode.SIGNUP -> {
                if (signupRoot == null) {
                    val stub = findViewById<ViewStub>(R.id.stub_signup)
                    signupRoot = stub.inflate()
                    bindSignupViews(signupRoot!!)
                }
                signupRoot?.visibility = View.VISIBLE
                loginRoot?.visibility = View.GONE
            }
        }
    }

    private fun bindLoginViews(root: View) {
        val emailField = root.findViewById<EditText>(R.id.login_email)
        val passwordField = root.findViewById<EditText>(R.id.login_password)
        val loginButton = root.findViewById<Button>(R.id.login_button)
        val signupText = root.findViewById<TextView>(R.id.login_signup_text)
        val toggleBtn = root.findViewById<ImageButton>(R.id.login_btn_toggle_password)
        val googleBtn = root.findViewById<Button>(R.id.login_btn_google)

        // Password toggle
        toggleBtn.setOnClickListener {
            passwordVisible = !passwordVisible
            if (passwordVisible) {
                passwordField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                toggleBtn.setImageResource(R.drawable.eye)
            } else {
                passwordField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                toggleBtn.setImageResource(R.drawable.eye_hidden)
            }
            passwordField.setSelection(passwordField.text.length)
        }

        // Google button handling
        val clientId = getString(R.string.default_web_client_id)
        if (clientId.isBlank() || clientId.contains("YOUR_DEFAULT_WEB_CLIENT_ID") || clientId.contains("YOUR_DEFAULT") || !::googleSignInClient.isInitialized) {
            googleBtn.isEnabled = false
            googleBtn.setOnClickListener {
                Toast.makeText(this, "Google Sign-In is not configured. Add your web client ID to strings.xml (default_web_client_id) and ensure google-services.json and SHA-1 are set in Firebase.", Toast.LENGTH_LONG).show()
            }
        } else {
            googleBtn.setOnClickListener {
                val signInIntent = googleSignInClient.signInIntent
                startActivityForResult(signInIntent, RC_SIGN_IN)
            }
        }

        loginButton.setOnClickListener {
            val email = emailField.text.toString()
            val password = passwordField.text.toString()

            if (email.isNotEmpty() && password.isNotEmpty()) {
                auth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener(this) { task ->
                        if (task.isSuccessful) {
                            val intent = Intent(this, MainActivity::class.java)
                            startActivity(intent)
                            finish()
                        } else {
                            Toast.makeText(baseContext, "Authentication failed.",
                                Toast.LENGTH_SHORT).show()
                        }
                    }
            } else {
                Toast.makeText(baseContext, "Please fill in all fields.",
                    Toast.LENGTH_SHORT).show()
            }
        }

        signupText.setOnClickListener {
            showMode(Mode.SIGNUP)
        }
    }

    private fun bindSignupViews(root: View) {
        val emailField = root.findViewById<EditText>(R.id.signup_email)
        val passwordField = root.findViewById<EditText>(R.id.signup_password)
        val confirmPasswordField = root.findViewById<EditText>(R.id.signup_confirm_password)
        val signupButton = root.findViewById<Button>(R.id.signup_button)
        val loginText = root.findViewById<TextView>(R.id.signup_login_text)
        val passwordRequirementsError = root.findViewById<TextView>(R.id.signup_password_requirements_error)
        val toggleBtn = root.findViewById<ImageButton>(R.id.signup_btn_toggle_password)
        val toggleConfirmBtn = root.findViewById<ImageButton>(R.id.signup_btn_toggle_confirm_password)
        val googleBtn = root.findViewById<Button>(R.id.signup_btn_google)

        toggleBtn.setOnClickListener {
            passwordVisible = !passwordVisible
            if (passwordVisible) {
                passwordField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                toggleBtn.setImageResource(R.drawable.eye)
            } else {
                passwordField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                toggleBtn.setImageResource(R.drawable.eye_hidden)
            }
            passwordField.setSelection(passwordField.text.length)
        }

        toggleConfirmBtn.setOnClickListener {
            val visible = confirmPasswordField.inputType == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
            if (visible) {
                confirmPasswordField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                toggleConfirmBtn.setImageResource(R.drawable.eye_hidden)
            } else {
                confirmPasswordField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                toggleConfirmBtn.setImageResource(R.drawable.eye)
            }
            confirmPasswordField.setSelection(confirmPasswordField.text.length)
        }

        // Google button handling
        val clientId = getString(R.string.default_web_client_id)
        if (clientId.isBlank() || clientId.contains("YOUR_DEFAULT_WEB_CLIENT_ID") || clientId.contains("YOUR_DEFAULT") || !::googleSignInClient.isInitialized) {
            googleBtn.isEnabled = false
            googleBtn.setOnClickListener {
                Toast.makeText(this, "Google Sign-In is not configured. Add your web client ID to strings.xml (default_web_client_id) and ensure google-services.json and SHA-1 are set in Firebase.", Toast.LENGTH_LONG).show()
            }
        } else {
            googleBtn.setOnClickListener {
                val signInIntent = googleSignInClient.signInIntent
                startActivityForResult(signInIntent, RC_SIGN_IN)
            }
        }

        signupButton.setOnClickListener {
            val email = emailField.text.toString()
            val password = passwordField.text.toString()
            val confirmPassword = confirmPasswordField.text.toString()

            if (email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
                Toast.makeText(baseContext, "Please fill in all fields.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password != confirmPassword) {
                Toast.makeText(baseContext, "Passwords do not match.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val error = getPasswordValidationError(password)
            if (error != null) {
                passwordRequirementsError.text = error
                passwordRequirementsError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        auth.signOut()
                        Toast.makeText(baseContext, "Sign up successful! Please log in.",
                            Toast.LENGTH_SHORT).show()
                        showMode(Mode.LOGIN)
                    } else {
                        Toast.makeText(baseContext, "Sign up failed: ${task.exception?.message}",
                            Toast.LENGTH_SHORT).show()
                    }
                }
        }

        loginText.setOnClickListener {
            showMode(Mode.LOGIN)
        }
    }

    private fun getPasswordValidationError(password: String): String? {
        if (password.length < 8) return "Password must be at least 8 characters."
        return null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    firebaseAuthWithGoogle(account.idToken!!)
                }
            } catch (e: ApiException) {
                Log.e("LoginActivity", "Google sign in failed", e)
                val code = e.statusCode
                Toast.makeText(this, "Google sign in failed (code=$code): ${e.message}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("LoginActivity", "Unexpected error during Google sign-in", e)
                Toast.makeText(this, "Google sign in failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val intent = Intent(this, MainActivity::class.java)
                    startActivity(intent)
                    finish()
                } else {
                    Toast.makeText(this, "Authentication failed.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    override fun onStart() {
        super.onStart()
        // Check if user is signed in (non-null) and update UI accordingly.
        val currentUser = auth.currentUser
        if (currentUser != null) {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_MODE, currentMode.name)
    }
}
