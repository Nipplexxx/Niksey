package com.example.niksey.ui.objects

import android.view.View
import androidx.fragment.app.Fragment
import com.example.niksey.R
import com.example.niksey.utillits.showToast
import com.example.niksey.utillits.signal.SignalManager

/**
 * Показывает предупреждение, если у кого-то из [peers] изменился ключ безопасности,
 * и даёт подтвердить новый ключ. Пока ключ не подтверждён, отправка этим собеседникам заблокирована.
 */
fun Fragment.bindIdentityBanner(peers: List<String>) {
    SignalManager.changedIdentities(peers) { changed ->
        activity?.runOnUiThread {
            val root = view ?: return@runOnUiThread
            val banner = root.findViewById<View>(R.id.chat_identity_warning) ?: return@runOnUiThread
            banner.visibility = if (changed.isEmpty()) View.GONE else View.VISIBLE

            root.findViewById<View>(R.id.chat_identity_accept)?.setOnClickListener {
                if (changed.isEmpty()) return@setOnClickListener
                var remaining = changed.size
                changed.forEach { peer ->
                    SignalManager.acceptNewIdentity(peer) { ok ->
                        activity?.runOnUiThread {
                            if (ok) showToast(getString(R.string.identity_confirmed))
                            remaining--
                            if (remaining == 0) bindIdentityBanner(peers)
                        }
                    }
                }
            }
        }
    }
}
