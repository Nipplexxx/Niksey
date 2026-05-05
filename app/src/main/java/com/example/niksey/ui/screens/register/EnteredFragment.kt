@file:Suppress("DEPRECATION")

package com.example.niksey.ui.screens.register

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.Fragment
import com.example.niksey.MainActivity
import com.example.niksey.R
import com.example.niksey.database.generateRandomFullname
import com.example.niksey.database.generateRandomUsername
import com.example.niksey.models.UserModel
import com.example.niksey.utillits.AUTH
import com.example.niksey.utillits.NODE_USERS
import com.example.niksey.utillits.REF_DATABASE_ROOT
import com.example.niksey.utillits.showToast
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.SignInButton
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener

@Suppress("DEPRECATION")
class EnteredFragment : Fragment(R.layout.fragment_entered) {
    private val RC_SIGN_IN = 9001

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val emailEditText: EditText? = view.findViewById(R.id.email_edit_text)
        val passwordEditText: EditText? = view.findViewById(R.id.password_edit_text)

        view.findViewById<Button>(R.id.email_sign_in_button).setOnClickListener {
            val email = emailEditText?.text.toString().trim()
            val password = passwordEditText?.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                showToast("Введите email и пароль")
                return@setOnClickListener
            }
            checkEmailAndLogin(email, password)
        }

        view.findViewById<Button>(R.id.anonymous_sign_in_button).setOnClickListener {
            signInAnonymously()
        }

        view.findViewById<SignInButton>(R.id.sign_in_button).setOnClickListener {
            signInWithGoogle()
        }
    }

    // ====================== EMAIL + PASSWORD ======================
    private fun checkEmailAndLogin(email: String, password: String) {
        REF_DATABASE_ROOT.child(NODE_USERS)
            .orderByChild("email")
            .equalTo(email)
            .addListenerForSingleValueEvent(object : ValueEventListener {

                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        // Почта уже есть в базе → проверяем пароль
                        val userMap = snapshot.children.firstOrNull()?.value as? Map<*, *>
                        val savedPassword = userMap?.get("password")?.toString() ?: ""

                        if (savedPassword == password) {
                            loginWithFirebaseAuth(email, password)
                        } else {
                            showToast("Неверный пароль")
                        }
                    } else {
                        // Почты нет → создаём новую учётку
                        registerNewUser(email, password)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    showToast("Ошибка сервера")
                }
            })
    }

    private fun loginWithFirebaseAuth(email: String, password: String) {
        AUTH.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    createOrUpdateUserProfile()
                } else {
                    showToast("Ошибка входа: ${task.exception?.message}")
                }
            }
    }

    private fun registerNewUser(email: String, password: String) {
        AUTH.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    createOrUpdateUserProfile()
                } else {
                    showToast("Не удалось создать аккаунт: ${task.exception?.message}")
                }
            }
    }

    // ====================== GOOGLE ======================
    private fun signInWithGoogle() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()

        val googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)
        startActivityForResult(googleSignInClient.signInIntent, RC_SIGN_IN)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                firebaseAuthWithGoogle(account)
            } catch (_: ApiException) {
                showToast(getString(R.string.auth_failed))
            }
        }
    }

    private fun firebaseAuthWithGoogle(acct: GoogleSignInAccount?) {
        val credential = GoogleAuthProvider.getCredential(acct?.idToken, null)
        AUTH.signInWithCredential(credential).addOnCompleteListener { task ->
            if (task.isSuccessful) createOrUpdateUserProfile()
            else showToast(getString(R.string.auth_failed))
        }
    }

    // ====================== ANONYMOUS ======================
    private fun signInAnonymously() {
        AUTH.signInAnonymously().addOnCompleteListener { task ->
            if (task.isSuccessful) createOrUpdateUserProfile()
            else showToast(getString(R.string.auth_failed))
        }
    }

    // ====================== СОЗДАНИЕ/ОБНОВЛЕНИЕ ПРОФИЛЯ ======================
    private fun createOrUpdateUserProfile() {
        val uid = AUTH.currentUser?.uid ?: return
        val email = AUTH.currentUser?.email ?: ""

        // Проверяем, существует ли уже профиль
        REF_DATABASE_ROOT.child(NODE_USERS).child(uid)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        // Профиль уже есть → загружаем его
                        val existingUser = snapshot.getValue(UserModel::class.java) ?: UserModel()
                        // Обновляем только email, если он изменился
                        if (existingUser.email != email) {
                            existingUser.email = email
                            REF_DATABASE_ROOT.child(NODE_USERS).child(uid).setValue(existingUser)
                        }
                        showToast(getString(R.string.welcome))
                        navigateToMainActivity()
                    } else {
                        // Профиля нет → создаём новый
                        val newUser = UserModel(
                            id = uid,
                            username = generateRandomUsername(),
                            fullname = generateRandomFullname(),
                            email = email
                        )
                        REF_DATABASE_ROOT.child(NODE_USERS).child(uid).setValue(newUser)
                            .addOnSuccessListener {
                                showToast(getString(R.string.welcome))
                                navigateToMainActivity()
                            }
                            .addOnFailureListener { showToast(it.message.toString()) }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    showToast("Ошибка загрузки профиля")
                }
            })
    }

    private fun navigateToMainActivity() {
        val intent = Intent(activity, MainActivity::class.java)
        startActivity(intent)
        activity?.finish()
    }
}