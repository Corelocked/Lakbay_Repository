package com.example.scenic_navigation.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.View
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

class SignupActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private val RC_SIGN_IN = 9002
    private var passwordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        auth = FirebaseAuth.getInstance()

        val emailField = findViewById<EditText>(R.id.email)
        val passwordField = findViewById<EditText>(R.id.password)
        val confirmPasswordField = findViewById<EditText>(R.id.confirm_password)
        val signupButton = findViewById<Button>(R.id.signup_button)
        val loginText = findViewById<TextView>(R.id.login_text)
        val passwordRequirementsError = findViewById<TextView>(R.id.password_requirements_error)
        val toggleBtn = findViewById<ImageButton>(R.id.btn_toggle_password)
        val toggleConfirmBtn = findViewById<ImageButton>(R.id.btn_toggle_confirm_password)
        val googleBtn = findViewById<Button>(R.id.btn_google_sign_in)

        passwordField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val password = s.toString()
                val error = getPasswordValidationError(password)
                if (error != null) {
                    passwordRequirementsError.text = error
                    passwordRequirementsError.visibility = View.VISIBLE
                } else {
                    passwordRequirementsError.visibility = View.GONE
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // Password toggles
        toggleBtn.setOnClickListener {
            passwordVisible = !passwordVisible
            if (passwordVisible) {
                passwordField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                toggleBtn.setImageResource(R.drawable.ic_visibility)
            } else {
                passwordField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                toggleBtn.setImageResource(R.drawable.ic_visibility_off)
            }
            passwordField.setSelection(passwordField.text.length)
        }
        toggleConfirmBtn.setOnClickListener {
            passwordVisible = !passwordVisible
            if (passwordVisible) {
                confirmPasswordField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                toggleConfirmBtn.setImageResource(R.drawable.ic_visibility)
            } else {
                confirmPasswordField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                toggleConfirmBtn.setImageResource(R.drawable.ic_visibility_off)
            }
            confirmPasswordField.setSelection(confirmPasswordField.text.length)
        }

        // Configure Google Sign-In safely
        val clientId = getString(R.string.default_web_client_id)
        if (clientId.isBlank() || clientId.contains("YOUR_DEFAULT_WEB_CLIENT_ID") || clientId.contains("YOUR_DEFAULT")) {
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

        signupButton.setOnClickListener {
            val email = emailField.text.toString()
            val password = passwordField.text.toString()
            val confirmPassword = confirmPasswordField.text.toString()

            if (email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
                Toast.makeText(baseContext, "Please fill in all fields.",
                    Toast.LENGTH_SHORT).show()
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
                        val intent = Intent(this, LoginActivity::class.java)
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(baseContext, "Sign up failed: ${task.exception?.message}",
                            Toast.LENGTH_SHORT).show()
                    }
                }
        }

        loginText.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
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
                Log.e("SignupActivity", "Google sign in failed", e)
                val code = e.statusCode
                Toast.makeText(this, "Google sign in failed (code=$code): ${e.message}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("SignupActivity", "Unexpected error during Google sign-in", e)
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

    private fun getPasswordValidationError(password: String): String? {
        if (password.length < 8) return "Password must be 8 characters or longer."
        if (password.length > 64) return "Password must be 64 characters or shorter."
        if (!password.any { it.isDigit() }) return "Password must have a number."
        if (!password.any { it.isLowerCase() }) return "Password must have a lowercase letter."
        if (!password.any { it.isUpperCase() }) return "Password must have an uppercase letter."
        if (!password.any { !it.isLetterOrDigit() }) return "Password must have a symbol."
        return null
    }
}
