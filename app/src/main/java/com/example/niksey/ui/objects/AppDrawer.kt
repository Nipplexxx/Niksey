package com.example.niksey.ui.objects

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.example.niksey.R
import com.example.niksey.ui.screens.main_list.MainListFragment
import com.example.niksey.ui.screens.phone_book.ContactsFragment
import com.example.niksey.ui.screens.settings.SettingsFragment
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.replaceFragment

/**
 * Навигация: плавающая стеклянная панель с тремя вкладками (Чаты · Контакты · Аккаунт).
 * Вкладки — корневые экраны без стека истории. Остальные экраны открываются поверх
 * и скрывают панель, в заголовке появляется стрелка «назад».
 * Имя класса сохранено от старого выдвижного меню, чтобы не менять все обращения к нему.
 */
class AppDrawer {

    enum class Tab { CHATS, CONTACTS, ACCOUNT }

    private lateinit var mNavBar: View
    private val mTabViews = mutableMapOf<Tab, View>()
    private var mCurrentTab: Tab? = null

    fun create() {
        mNavBar = APP_ACTIVITY.findViewById(R.id.nav_bar)
        mTabViews[Tab.CHATS] = APP_ACTIVITY.findViewById(R.id.nav_tab_chats)
        mTabViews[Tab.CONTACTS] = APP_ACTIVITY.findViewById(R.id.nav_tab_contacts)
        mTabViews[Tab.ACCOUNT] = APP_ACTIVITY.findViewById(R.id.nav_tab_account)

        mTabViews.forEach { (tab, view) -> view.setOnClickListener { openTab(tab) } }
    }

    /** Переключает вкладку без стека: «назад» из корневой вкладки не возвращает на предыдущую. */
    fun openTab(tab: Tab) {
        if (tab == mCurrentTab) return
        val fragment = when (tab) {
            Tab.CHATS -> MainListFragment()
            Tab.CONTACTS -> ContactsFragment()
            Tab.ACCOUNT -> SettingsFragment()
        }
        replaceFragment(fragment, addStack = false)
    }

    /** Корневой экран вкладки: панель видна, стрелки нет. */
    fun showRoot(tab: Tab) {
        mCurrentTab = tab
        APP_ACTIVITY.supportActionBar?.setDisplayHomeAsUpEnabled(false)
        APP_ACTIVITY.mToolbar.navigationIcon = null
        APP_ACTIVITY.mToolbar.setNavigationOnClickListener(null)
        APP_ACTIVITY.mToolbar.isVisible = true
        renderSelection(tab)
        setBarVisible(true)
    }

    /** Вложенный экран (чат, настройки профиля и т.п.): панель скрыта, в заголовке «назад». */
    fun showSubScreen() {
        APP_ACTIVITY.supportActionBar?.setDisplayHomeAsUpEnabled(true)
        APP_ACTIVITY.mToolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        APP_ACTIVITY.mToolbar.setNavigationOnClickListener {
            APP_ACTIVITY.supportFragmentManager.popBackStack()
        }
        APP_ACTIVITY.mToolbar.isVisible = true
        setBarVisible(false)
    }

    /** Чат использует собственную шапку: системную панель заголовка скрываем, чтобы не было двух полос. */
    fun setToolbarVisible(visible: Boolean) {
        APP_ACTIVITY.mToolbar.isVisible = visible
    }

    private fun renderSelection(selected: Tab) {
        mTabViews.forEach { (tab, view) ->
            val isSelected = tab == selected
            view.background = if (isSelected) APP_ACTIVITY.getDrawable(R.drawable.bg_nav_pill) else null

            val iconColor = APP_ACTIVITY.getColor(if (isSelected) R.color.accent_cyan else R.color.text_hint)
            val labelColor = APP_ACTIVITY.getColor(if (isSelected) R.color.text_primary else R.color.text_hint)

            // Структура вкладки: [ImageView, TextView] внутри LinearLayout (см. activity_main.xml)
            val content = view as ViewGroup
            val column = content.getChildAt(0) as ViewGroup
            (column.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(iconColor)
            (column.getChildAt(1) as TextView).setTextColor(labelColor)
        }
    }

    private fun setBarVisible(visible: Boolean) {
        mNavBar.animate().cancel()
        val offset = 48f * mNavBar.resources.displayMetrics.density

        if (visible) {
            if (mNavBar.isVisible && mNavBar.alpha == 1f) return
            mNavBar.visibility = View.VISIBLE
            mNavBar.translationY = offset
            mNavBar.alpha = 0f
            mNavBar.animate().translationY(0f).alpha(1f).setDuration(220).start()
        } else {
            if (!mNavBar.isVisible) return
            mNavBar.animate().translationY(offset).alpha(0f).setDuration(180)
                .withEndAction {
                    mNavBar.visibility = View.GONE
                    mNavBar.translationY = 0f
                    mNavBar.alpha = 1f
                }
                .start()
        }
    }
}
