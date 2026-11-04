@file:Suppress("DEPRECATION")

package com.example.niksey.ui.screens.main_list

import android.os.Bundle
import android.view.*
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.models.CommonModel
import com.example.niksey.ui.objects.AppDrawer
import com.example.niksey.ui.screens.groups_messages.AddContactsFragment
import com.example.niksey.ui.viewmodels.MainListViewModel
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.replaceFragment
import com.example.niksey.utillits.showToast

/**
 * Экран «Чаты». Данные и их загрузка — в [MainListViewModel]; фрагмент только показывает
 * текущий список и применяет поиск. Поэтому возврат из чата или группы отображает список сразу.
 */
class MainListFragment : Fragment(R.layout.fragment_main_list) {

    private lateinit var mRecyclerView: RecyclerView
    private lateinit var mAdapter: MainListAdapter
    private lateinit var mEmptyState: View

    private val viewModel by lazy { ViewModelProvider(APP_ACTIVITY)[MainListViewModel::class.java] }

    /** Полный список и текущий поисковый запрос (поиск — только отображение, не данные). */
    private var allChats: List<CommonModel> = emptyList()
    private var loaded = false
    private var searchQuery: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)
        initViews(view)

        // Наблюдатели подключаются до start(): текущий список (включая кэш) приходит сразу
        viewModel.chats.observe(viewLifecycleOwner) { list ->
            allChats = list
            applyFilter()
        }
        viewModel.loaded.observe(viewLifecycleOwner) { isLoaded ->
            loaded = isLoaded
            updateEmptyState()
        }
        viewModel.error.observe(viewLifecycleOwner) { message ->
            if (message != null) {
                showToast(APP_ACTIVITY.getString(R.string.error_generic, message))
                viewModel.clearError()
            }
        }
        viewModel.start()
    }

    override fun onResume() {
        super.onResume()
        APP_ACTIVITY.mToolbar.title = getString(R.string.app_name)
        APP_ACTIVITY.mAppDrawer.showRoot(AppDrawer.Tab.CHATS)
        // Возврат из чата: пересчитываем счётчики непрочитанных (метка «прочитано» обновилась)
        viewModel.refreshUnread()
    }

    private fun initViews(view: View) {
        mRecyclerView = view.findViewById(R.id.main_list_recycle_view)
        mEmptyState = view.findViewById(R.id.main_empty_state)
        view.findViewById<View>(R.id.main_fab_create_group).setOnClickListener {
            replaceFragment(AddContactsFragment())
        }
        mAdapter = MainListAdapter()
        mRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = mAdapter
            setHasFixedSize(true)
        }
    }

    /** Показывает список с учётом поиска. */
    private fun applyFilter() {
        val query = searchQuery?.trim()?.lowercase()
        val visible = if (query.isNullOrEmpty()) {
            allChats
        } else {
            allChats.filter {
                it.fullname.lowercase().contains(query) ||
                        it.phone.lowercase().contains(query) ||
                        it.username.lowercase().contains(query)
            }
        }
        mAdapter.submitList(visible)
        updateEmptyState()
    }

    /** Заглушку «нет чатов» показываем только после ответа сети, а не во время ожидания. */
    private fun updateEmptyState() {
        if (!::mEmptyState.isInitialized) return
        mEmptyState.isVisible = loaded && allChats.isEmpty()
        mRecyclerView.isVisible = allChats.isNotEmpty()
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.main_list_menu, menu)

        val searchItem = menu.findItem(R.id.menu_search)
        val searchView = searchItem.actionView as SearchView

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText
                applyFilter()
                return true
            }
        })
    }
}
