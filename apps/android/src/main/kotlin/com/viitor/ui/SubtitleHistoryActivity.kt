package com.viitor.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.viitor.R
import com.viitor.databinding.ActivitySubtitleHistoryBinding
import com.viitor.db.AndTranslateDatabase
import com.viitor.db.SubtitleHistory
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class SubtitleHistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySubtitleHistoryBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubtitleHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        
        loadHistory()

        binding.btnClearHistory.setOnClickListener {
            scope.launch(Dispatchers.IO) {
                AndTranslateDatabase.getDatabase(this@SubtitleHistoryActivity).historyDao().deleteAll()
                withContext(Dispatchers.Main) {
                    loadHistory()
                }
            }
        }
    }

    private fun loadHistory() {
        scope.launch(Dispatchers.IO) {
            val history = AndTranslateDatabase.getDatabase(this@SubtitleHistoryActivity)
                .historyDao()
                .getAllHistorySync()
            withContext(Dispatchers.Main) {
                binding.rvHistory.adapter = HistoryAdapter(history)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    inner class HistoryAdapter(private val list: List<SubtitleHistory>) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTimestamp: TextView = view.findViewById(R.id.tv_timestamp)
            val tvOriginal: TextView = view.findViewById(R.id.tv_original)
            val tvTranslated: TextView = view.findViewById(R.id.tv_translated)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_subtitle_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            holder.tvTimestamp.text = sdf.format(Date(item.timestamp))
            holder.tvOriginal.text = item.originalText
            holder.tvTranslated.text = item.translatedText ?: ""
        }

        override fun getItemCount(): Int = list.size
    }
}
