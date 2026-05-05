package com.example.niksey.ui.screens.settings

import android.widget.EditText
import com.example.niksey.R
import com.example.niksey.database.setPasswordToDatabase
import com.example.niksey.ui.screens.base_fragment.BaseChangeFragment
import com.example.niksey.utillits.USER

@Suppress("DEPRECATION")
class ChangePasswordFragment : BaseChangeFragment(R.layout.fragment_change_password) {

    override fun onResume() {
        super.onResume()
        view?.findViewById<EditText>(R.id.settings_input_password)?.setText(USER.password)
    }

    override fun change() {
        super.change()
        val newPassword = view?.findViewById<EditText>(R.id.settings_input_password)?.text.toString()
        setPasswordToDatabase(newPassword)
    }
}
