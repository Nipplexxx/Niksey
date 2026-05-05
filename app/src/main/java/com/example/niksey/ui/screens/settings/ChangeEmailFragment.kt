package com.example.niksey.ui.screens.settings

import android.widget.EditText
import com.example.niksey.R
import com.example.niksey.database.setEmailToDatabase
import com.example.niksey.ui.screens.base_fragment.BaseChangeFragment
import com.example.niksey.utillits.USER

@Suppress("DEPRECATION")
class ChangeEmailFragment : BaseChangeFragment(R.layout.fragment_change_email) {

    override fun onResume() {
        super.onResume()
        view?.findViewById<EditText>(R.id.settings_input_email)?.setText(USER.email)
    }

    override fun change() {
        super.change()
        val newEmail = view?.findViewById<EditText>(R.id.settings_input_email)?.text.toString()
        setEmailToDatabase(newEmail)
    }
}
