package com.example.scenic_navigation.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()

        val emailField = findViewById<EditText>(R.id.email)
        val passwordField = findViewById<EditText>(R.id.password)
        val loginButton = findViewById<Button>(R.id.login_button)
        val signupText = findViewById<TextView>(R.id.signup_text)
        val toggleBtn = findViewById<ImageButton>(R.id.btn_toggle_password)
        val googleBtn = findViewById<Button>(R.id.btn_google_sign_in)

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

        // Configure Google Sign-In: ensure the client id is set (not the placeholder)
        val clientId = getString(R.string.default_web_client_id)
        if (clientId.isBlank() || clientId.contains("YOUR_DEFAULT_WEB_CLIENT_ID") || clientId.contains("YOUR_DEFAULT")) {
            // Not configured — disable Google button and show clear guidance
            googleBtn.isEnabled = false
            googleBtn.setOnClickListener {
                Toast.makeText(this, "Google Sign-In is not configured. Add your web client ID to strings.xml (default_web_client_id) and ensure google-services.json and SHA-1 are set in Firebase.", Toast.LENGTH_LONG).show()
            }
        } else {
            try {
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(clientId)
                    .requestEmail()
                    .build()
                googleSignInClient = GoogleSignIn.getClient(this, gso)

                googleBtn.setOnClickListener {
                    val signInIntent = googleSignInClient.signInIntent
                    startActivityForResult(signInIntent, RC_SIGN_IN)
                }
            } catch (e: Exception) {
                googleBtn.isEnabled = false
                googleBtn.setOnClickListener {
                    Toast.makeText(this, "Failed to initialize Google Sign-In: ${e.message}", Toast.LENGTH_LONG).show()
                }
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
            val intent = Intent(this, SignupActivity::class.java)
            startActivity(intent)
        }
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
}
