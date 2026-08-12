package com.rakibulcodes.callerinfo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.rakibulcodes.callerinfo.data.database.CallerInfoEntity
import com.rakibulcodes.callerinfo.databinding.ItemHistoryCardBinding

class HistoryAdapter(
    private var items: List<CallerInfoEntity>,
    private val onSave: (CallerInfoEntity) -> Unit,
    private val onCopy: (CallerInfoEntity) -> Unit,
    private val onShare: (CallerInfoEntity) -> Unit,
    private val onDelete: (CallerInfoEntity) -> Unit,
    private val onDial: (CallerInfoEntity) -> Unit,
    private val onFavorite: (CallerInfoEntity) -> Unit,
    private val onEdit: (CallerInfoEntity) -> Unit,
    private val normalizeNumber: (String) -> String
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemHistoryCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemHistoryCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        with(holder.binding.cardContent) {
            tvName.text = item.displayName() ?: "Unknown"
            tvNumber.text = item.number
            val carrierText = listOfNotNull(item.carrier, item.country).joinToString(", ")
            tvCarrier.text = if (carrierText.isNotEmpty()) carrierText else "Unknown Carrier"
            tvEmail.visibility = if (!item.email.isNullOrEmpty()) View.VISIBLE else View.GONE
            if (!item.email.isNullOrEmpty()) tvEmail.text = item.email
            tvLocation.visibility = if (!item.location.isNullOrEmpty()) View.VISIBLE else View.GONE
            if (!item.location.isNullOrEmpty()) tvLocation.text = item.location
            val fullAddress = listOfNotNull(item.address1, item.address2).joinToString("\n")
            tvAddress.visibility = if (fullAddress.isNotEmpty()) View.VISIBLE else View.GONE
            if (fullAddress.isNotEmpty()) tvAddress.text = fullAddress
            tvUserNote.visibility = if (!item.userNote.isNullOrBlank()) View.VISIBLE else View.GONE
            if (!item.userNote.isNullOrBlank()) tvUserNote.text = item.userNote
            tvTime.visibility = View.VISIBLE
            tvTime.text = getShortTimeSpan(item.lastSuccessfullyUpdatedMillis ?: item.timestamp)
        }
        holder.binding.btnHistorySave.setOnClickListener { onSave(item) }
        holder.binding.btnHistoryCopy.setOnClickListener { onCopy(item) }
        holder.binding.btnHistoryShare.setOnClickListener { onShare(item) }
        holder.binding.btnHistoryDelete.setOnClickListener { onDelete(item) }
        holder.binding.btnHistoryDial.setOnClickListener { onDial(item) }
        holder.binding.btnHistoryFavorite.text = if (item.favorite) "★ Favorite" else "☆ Favorite"
        holder.binding.btnHistoryFavorite.setOnClickListener { onFavorite(item) }
        holder.binding.btnHistoryEdit.setOnClickListener { onEdit(item) }
    }

    private fun getShortTimeSpan(time: Long): String {
        val diff = (System.currentTimeMillis() - time).coerceAtLeast(0)
        return when {
            diff < 60000 -> "now"
            diff < 3600000 -> "${diff / 60000}m"
            diff < 86400000 -> "${diff / 3600000}h"
            diff < 2592000000 -> "${diff / 86400000}d"
            diff < 31536000000 -> "${diff / 2592000000}mo"
            else -> "${diff / 31536000000}y"
        }
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<CallerInfoEntity>) {
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldNumber = items[oldItemPosition].number
                val newNumber = newItems[newItemPosition].number
                val oldLookupValue = normalizeNumber(oldNumber)
                val newLookupValue = normalizeNumber(newNumber)
                return if (oldLookupValue.isNotEmpty() && newLookupValue.isNotEmpty()) {
                    oldLookupValue == newLookupValue
                } else oldNumber == newNumber
            }
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                items[oldItemPosition] == newItems[newItemPosition]
        })
        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }
}
