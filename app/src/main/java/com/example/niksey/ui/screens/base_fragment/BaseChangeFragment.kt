package com.example.niksey.ui.screens.base_fragment

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.fragment.app.Fragment
import com.example.niksey.MainActivity
import com.example.niksey.R
import com.mikepenz.materialize.util.KeyboardUtil.hideKeyboard

/* Базовый фрагмент, от него наследуются фрагменты где происходит изменение данных о пользователе. */
@Suppress("DEPRECATION")
open class BaseChangeFragment (layout:Int): Fragment(layout) {
    override fun onStart() {
        super.onStart()
        setHasOptionsMenu(true)
        (activity as MainActivity).mAppDrawer.disableDrawer()
        hideKeyboard(activity)
    }

    override fun onStop() {
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        /* Создание выпадающего меню*/
        (activity as MainActivity).menuInflater.inflate(R.menu.settings_menu_confirm, menu)
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        /* Слушатель выбора пункта выпадающего меню */
        when (item.itemId) {
            R.id.settings_confirm_change -> change()
        }
        return true
    }

    open fun change() {
    }
}