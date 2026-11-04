package com.example.niksey.ui.screens.main_list

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.models.CommonModel
import com.example.niksey.ui.screens.groups_messages.GroupChatFragment
import com.example.niksey.ui.screens.private_messages.SingleChatFragment
import com.example.niksey.utillits.TYPE_CHAT
import com.example.niksey.utillits.TYPE_GROUP
import com.example.niksey.utillits.downloadAndSetImage
import com.example.niksey.utillits.replaceFragment
import com.example.niksey.utillits.showToast
import de.hdodenhof.circleimageview.CircleImageView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Список чатов на главном экране (как в Telegram).
 *
 * Строка: аватар (+ точка онлайн), имя, превью последнего сообщения, время
 * и плашка счётчика непрочитанных.
 *
 * submitList использует DiffUtil: при приходе нового чата или расшифровке превью
 * список обновляется точечно, без полной перерисовки.
 */
class MainListAdapter : RecyclerView.Adapter<MainListAdapter.MainListHolder>() {

    private var listItems = mutableListOf<CommonModel>()

    class MainListHolder(view: View) : RecyclerView.ViewHolder(view) {
        val itemName: TextView = view.findViewById(R.id.main_list_item_name)
        val itemLastMessage: TextView = view.findViewById(R.id.main_list_last_message)
        val itemPhoto: CircleImageView = view.findViewById(R.id.main_list_item_photo)
        val itemTime: TextView = view.findViewById(R.id.main_list_time)
        val itemUnreadCount: TextView = view.findViewById(R.id.main_list_unread_count)
        val itemOnlineDot: View = view.findViewById(R.id.main_list_online_dot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MainListHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.main_list_item, parent, false)
        return MainListHolder(view)
    }

    override fun getItemCount(): Int = listItems.size

    override fun onBindViewHolder(holder: MainListHolder, position: Int) {
        // DiffUtil dispatches updates asynchronously relative to our list swap;
        // a stale position can briefly arrive — guard instead of crash
        if (position !in listItems.indices) return
        val item = listItems[position]

        holder.itemName.text = item.fullname.ifEmpty { item.username }
        holder.itemLastMessage.text = item.lastMessage.ifEmpty { "Нет сообщений" }
        holder.itemPhoto.downloadAndSetImage(item.photoUrl)

        // timeStamp хранится как Any (Long с сервера либо 0L) — приводим аккуратно
        val ts = (item.timeStamp as? Long) ?: 0L
        holder.itemTime.isVisible = ts > 0
        if (ts > 0) holder.itemTime.text = formatChatTime(ts)

        // Счётчик непрочитанных: показываем только для чужих сообщений
        val unread = item.unreadCount
        holder.itemUnreadCount.isVisible = unread > 0
        if (unread > 0) {
            holder.itemUnreadCount.text = if (unread > 99) "99+" else unread.toString()
        }

        // Точка онлайн: только для личных чатов и только если собеседник онлайн
        holder.itemOnlineDot.isVisible = item.type != TYPE_GROUP && item.state == "online"

        holder.itemView.setOnClickListener {
            // Тип записи в main_list: chat — личный чат, group — групповой.
            // Исторически в type мог попасть и тип последнего сообщения (text/voice/...) —
            // все они означают личный чат, поэтому проверяем их тоже.
            val type = item.type.lowercase()
            val isPrivate = type == TYPE_CHAT || type in setOf("text", "voice", "image", "file", "video")

            when {
                isPrivate -> replaceFragment(SingleChatFragment.newInstance(item.id))
                type == TYPE_GROUP -> replaceFragment(GroupChatFragment.newInstance(item))
                else -> showToast("Неизвестный тип чата: ${item.type}")
            }
        }
    }

    fun submitList(newList: List<CommonModel>) {
        val diff = DiffUtil.calculateDiff(ListDiff(listItems, newList))
        listItems.clear()
        listItems.addAll(newList)
        diff.dispatchUpdatesTo(this)
    }

    /**
     * Время как в Telegram: «14:28» для сегодня, «Пн» — для этой недели,
     * полная дата — для старых сообщений.
     */
    private fun formatChatTime(timeMillis: Long): String {
        val now = Calendar.getInstance()
        val time = Calendar.getInstance().apply { timeInMillis = timeMillis }

        return if (now.get(Calendar.YEAR) == time.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == time.get(Calendar.DAY_OF_YEAR)
        ) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMillis))
        } else if (now.timeInMillis - timeMillis < WEEK_MS) {
            // Короткое имя дня недели (пн, вт…)
            SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timeMillis))
        } else {
            SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(Date(timeMillis))
        }
    }

    /** Чат — тот же, если совпадает id; содержимое — если ещё и превью/время/непрочитанные совпали. */
    private class ListDiff(
        private val oldList: List<CommonModel>,
        private val newList: List<CommonModel>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size
        override fun areItemsTheSame(old: Int, new: Int) =
            oldList[old].id == newList[new].id
        override fun areContentsTheSame(old: Int, new: Int) =
            oldList[old].lastMessage == newList[new].lastMessage &&
                    oldList[old].timeStamp == newList[new].timeStamp &&
                    oldList[old].unreadCount == newList[new].unreadCount &&
                    oldList[old].state == newList[new].state
    }

    private companion object {
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}
