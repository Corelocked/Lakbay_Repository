package com.example.scenic_navigation.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.scenic_navigation.R
import com.google.firebase.auth.FirebaseAuth

class SignupActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

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
                Toast.makeText(baseContext, "Passwords do not match.",
                    Toast.LENGTH_SHORT).show()
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
