package com.example.niksey.ui.screens.settings

import android.widget.EditText
import com.example.niksey.R
import com.example.niksey.database.setPhoneToDatabase
import com.example.niksey.ui.screens.base_fragment.BaseChangeFragment
import com.example.niksey.utillits.USER

@Suppress("DEPRECATION")
class ChangePhoneFragment : BaseChangeFragment(R.layout.fragment_change_phone) {

    override fun onResume() {
        super.onResume()
        view?.findViewById<EditText>(R.id.settings_input_phone)?.setText(USER.phone)
    }

    override fun change() {
        super.change()
        val newPassword = view?.findViewById<EditText>(R.id.settings_input_phone)?.text.toString()
        setPhoneToDatabase(newPassword)
    }
}
