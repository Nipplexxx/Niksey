package com.example.niksey.ui.screens.settings

import android.widget.EditText
import com.example.niksey.R
import com.example.niksey.database.setBioToDatabase
import com.example.niksey.ui.screens.base_fragment.BaseChangeFragment
import com.example.niksey.utillits.*

@Suppress("DEPRECATION")
class ChangeBioFragment : BaseChangeFragment(R.layout.fragment_change_bio) {

    override fun onResume() {
        super.onResume()
        view?.findViewById<EditText>(R.id.settings_input_bio)?.setText(USER.bio)
    }

    override fun change() {
        super.change()
        val newBio = view?.findViewById<EditText>(R.id.settings_input_bio)?.text.toString()
        setBioToDatabase(newBio)
    }
}