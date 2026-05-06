package com.example.niksey.ui.objects

import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.example.niksey.R
import com.example.niksey.ui.screens.groups_messages.AddContactsFragment
import com.example.niksey.ui.screens.other_fragment.InformationFragment
import com.example.niksey.ui.screens.phone_book.ContactsFragment
import com.example.niksey.ui.screens.settings.SettingsFragment
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.USER
import com.example.niksey.utillits.downloadAndSetImage
import com.example.niksey.utillits.replaceFragment
import com.google.android.material.navigation.NavigationView

class AppDrawer {

    private lateinit var mDrawerLayout: DrawerLayout
    private lateinit var mNavigationView: NavigationView
    private lateinit var mToggle: ActionBarDrawerToggle

    fun create() {
        mDrawerLayout = APP_ACTIVITY.findViewById(R.id.drawer_layout)
        mNavigationView = APP_ACTIVITY.findViewById(R.id.nav_view)

        setupHeader()
        setupMenu()
        setupToggle()

        // Обновляем данные при создании (на случай, если они изменились)
        updateHeader()
    }

    private fun setupToggle() {
        mToggle = ActionBarDrawerToggle(
            APP_ACTIVITY,
            mDrawerLayout,
            APP_ACTIVITY.mToolbar,
            R.string.open_drawer,
            R.string.close_drawer
        )
        mDrawerLayout.addDrawerListener(mToggle)
        mToggle.syncState()
    }

    fun disableDrawer() {
        mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        // Показываем стрелку назад
        APP_ACTIVITY.supportActionBar?.setDisplayHomeAsUpEnabled(true)
        APP_ACTIVITY.mToolbar.setNavigationIcon(R.drawable.ic_arrow_back)

        APP_ACTIVITY.mToolbar.setNavigationOnClickListener {
            APP_ACTIVITY.supportFragmentManager.popBackStack()
        }
    }

    fun enableDrawer() {
        mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        mToggle.syncState()

        // Возвращаем гамбургер
        APP_ACTIVITY.supportActionBar?.setDisplayHomeAsUpEnabled(false)
        mToggle.syncState()

        APP_ACTIVITY.mToolbar.setNavigationOnClickListener {
            mDrawerLayout.openDrawer(GravityCompat.START)
        }
    }

    private fun setupHeader() {
        val headerView = mNavigationView.getHeaderView(0)

        val nameTextView = headerView.findViewById<TextView>(R.id.header_name)
        val phoneTextView = headerView.findViewById<TextView>(R.id.header_phone)
        val avatarImageView = headerView.findViewById<ImageView>(R.id.header_avatar)

        nameTextView.text = USER.fullname.ifEmpty { "Пользователь" }
        phoneTextView.text = USER.phone.ifEmpty { "Нет номера" }

        if (USER.photoUrl.isNotEmpty()) {
            avatarImageView.downloadAndSetImage(USER.photoUrl)
        } else {
            avatarImageView.setImageResource(R.drawable.ic_person)
        }
    }

    fun updateHeader() {
        val headerView = mNavigationView.getHeaderView(0)

        val nameTextView = headerView.findViewById<TextView>(R.id.header_name)
        val phoneTextView = headerView.findViewById<TextView>(R.id.header_phone)
        val avatarImageView = headerView.findViewById<ImageView>(R.id.header_avatar)

        nameTextView.text = USER.fullname.ifEmpty { "Пользователь" }
        phoneTextView.text = USER.phone.ifEmpty { "Нет номера" }

        if (USER.photoUrl.isNotEmpty()) {
            avatarImageView.downloadAndSetImage(USER.photoUrl)
        } else {
            avatarImageView.setImageResource(R.drawable.ic_person)
        }
    }

    private fun setupMenu() {
        mNavigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_create_group -> replaceFragment(AddContactsFragment())
                R.id.nav_contacts -> replaceFragment(ContactsFragment())
                R.id.nav_personal_account -> replaceFragment(SettingsFragment())
                R.id.nav_information -> replaceFragment(InformationFragment())
            }
            mDrawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }
}