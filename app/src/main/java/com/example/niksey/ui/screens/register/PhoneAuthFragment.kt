@file:Suppress("DEPRECATION")

package com.example.niksey.ui.screens.register

import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
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
import com.google.firebase.FirebaseException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.hbb20.CountryCodePicker
import java.util.concurrent.TimeUnit

class PhoneAuthFragment : Fragment(R.layout.fragment_phone_auth) {

    private lateinit var ccp: CountryCodePicker
    private lateinit var phoneInput: EditText
    private lateinit var codeInputs: List<EditText>
    private lateinit var btnSendCode: Button
    private lateinit var btnVerifyCode: Button
    private lateinit var btnResendCode: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView

    private var verificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null
    private var countDownTimer: CountDownTimer? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ccp = view.findViewById(R.id.ccp)
        phoneInput = view.findViewById(R.id.phone_input)
        btnSendCode = view.findViewById(R.id.btn_send_code)
        btnVerifyCode = view.findViewById(R.id.btn_verify_code)
        btnResendCode = view.findViewById(R.id.btn_resend_code)
        progressBar = view.findViewById(R.id.progress_bar)
        tvStatus = view.findViewById(R.id.tv_status)
        tvTimer = view.findViewById(R.id.tv_timer)

        codeInputs = listOf(
            view.findViewById(R.id.code_1),
            view.findViewById(R.id.code_2),
            view.findViewById(R.id.code_3),
            view.findViewById(R.id.code_4),
            view.findViewById(R.id.code_5),
            view.findViewById(R.id.code_6)
        )

        setupCodeInputs()
        btnSendCode.setOnClickListener { sendVerificationCode() }
        btnVerifyCode.setOnClickListener { verifyCode() }
        btnResendCode.setOnClickListener { resendVerificationCode() }

        showPhoneInputState()
    }

    private fun setupCodeInputs() {
        codeInputs.forEachIndexed { index, editText ->
            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (s?.length == 1 && index < codeInputs.size - 1) {
                        codeInputs[index + 1].requestFocus()
                    }
                    if (s?.length == 0 && index > 0) {
                        codeInputs[index - 1].requestFocus()
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
    }

    private fun getFullPhoneNumber(): String {
        val countryCode = ccp.selectedCountryCodeWithPlus
        val number = phoneInput.text.toString().trim()
        return "$countryCode$number"
    }

    private fun showPhoneInputState() {
        ccp.visibility = View.VISIBLE
        phoneInput.visibility = View.VISIBLE
        view?.findViewById<View>(R.id.code_container)?.visibility = View.GONE
        btnSendCode.visibility = View.VISIBLE
        btnVerifyCode.visibility = View.GONE
        btnResendCode.visibility = View.GONE
        tvTimer.visibility = View.GONE
        tvStatus.text = "Выберите страну и введите номер"
    }

    private fun showCodeInputState() {
        ccp.visibility = View.GONE
        phoneInput.visibility = View.GONE
        view?.findViewById<View>(R.id.code_container)?.visibility = View.VISIBLE
        btnSendCode.visibility = View.GONE
        btnVerifyCode.visibility = View.VISIBLE
        btnResendCode.visibility = View.VISIBLE
        tvTimer.visibility = View.VISIBLE
        tvStatus.text = "Введите код из SMS"
        startResendTimer()
        codeInputs[0].requestFocus()
    }

    private fun sendVerificationCode() {
        val phoneNumber = getFullPhoneNumber()
        if (phoneNumber.length < 10) {
            showToast("Введите корректный номер телефона")
            return
        }
        progressBar.visibility = View.VISIBLE
        btnSendCode.isEnabled = false

        val options = PhoneAuthOptions.newBuilder(AUTH)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(requireActivity())
            .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    progressBar.visibility = View.GONE
                    signInWithPhoneAuthCredential(credential)
                }
                override fun onVerificationFailed(e: FirebaseException) {
                    progressBar.visibility = View.GONE
                    btnSendCode.isEnabled = true
                    showToast("Ошибка: ${e.message}")
                }
                override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
                    this@PhoneAuthFragment.verificationId = verificationId
                    resendToken = token
                    progressBar.visibility = View.GONE
                    btnSendCode.isEnabled = true
                    showCodeInputState()
                    showToast("Код отправлен")
                }
            })
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    private fun resendVerificationCode() {
        val phoneNumber = getFullPhoneNumber()
        if (resendToken == null) return
        progressBar.visibility = View.VISIBLE
        btnResendCode.isEnabled = false

        val options = PhoneAuthOptions.newBuilder(AUTH)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(requireActivity())
            .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    progressBar.visibility = View.GONE
                    signInWithPhoneAuthCredential(credential)
                }
                override fun onVerificationFailed(e: FirebaseException) {
                    progressBar.visibility = View.GONE
                    btnResendCode.isEnabled = true
                    showToast("Ошибка: ${e.message}")
                }
                override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
                    this@PhoneAuthFragment.verificationId = verificationId
                    resendToken = token
                    progressBar.visibility = View.GONE
                    btnResendCode.isEnabled = true
                    showToast("Код отправлен повторно")
                    startResendTimer()
                }
            })
            .setForceResendingToken(resendToken!!)
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    private fun verifyCode() {
        val code = codeInputs.joinToString("") { it.text.toString() }
        if (code.length != 6) {
            showToast("Введите полный 6-значный код")
            return
        }
        val verificationId = this.verificationId ?: return
        progressBar.visibility = View.VISIBLE
        btnVerifyCode.isEnabled = false
        val credential = PhoneAuthProvider.getCredential(verificationId, code)
        signInWithPhoneAuthCredential(credential)
    }

    private fun signInWithPhoneAuthCredential(credential: PhoneAuthCredential) {
        AUTH.signInWithCredential(credential)
            .addOnCompleteListener { task ->
                progressBar.visibility = View.GONE
                btnVerifyCode.isEnabled = true
                if (task.isSuccessful) {
                    createOrUpdateUserProfile()
                } else {
                    showToast("Ошибка входа: ${task.exception?.message}")
                }
            }
    }

    private fun createOrUpdateUserProfile() {
        val uid = AUTH.currentUser?.uid ?: return
        val phone = getFullPhoneNumber()
        REF_DATABASE_ROOT.child(NODE_USERS).child(uid)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        showToast(getString(R.string.welcome))
                        navigateToMainActivity()
                    } else {
                        val newUser = UserModel(id = uid, username = generateRandomUsername(), fullname = generateRandomFullname(), phone = phone)
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

    private fun startResendTimer() {
        btnResendCode.isEnabled = false
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(60000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                tvTimer.text = "Повторная отправка через ${millisUntilFinished / 1000} сек"
            }
            override fun onFinish() {
                tvTimer.text = ""
                btnResendCode.isEnabled = true
            }
        }.start()
    }

    private fun navigateToMainActivity() {
        val intent = android.content.Intent(activity, MainActivity::class.java)
        startActivity(intent)
        activity?.finish()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        countDownTimer?.cancel()
    }
}