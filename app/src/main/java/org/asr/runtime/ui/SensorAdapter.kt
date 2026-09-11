package org.asr.runtime.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import org.asr.runtime.R

class SensorAdapter(
    private val onToggle: (SensorUiModel) -> Unit,
    private val onStart: (SensorUiModel) -> Unit,
    private val onStop: (SensorUiModel) -> Unit
) : ListAdapter<SensorUiModel, SensorAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rowCollapsed: View = view.findViewById(R.id.rowCollapsed)
        val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvRate: TextView = view.findViewById(R.id.tvRate)
        val statusCombined: StatusIndicatorView = view.findViewById(R.id.statusCombined)
        val ivChevron: ImageView = view.findViewById(R.id.ivChevron)

        val dividerExpand: View = view.findViewById(R.id.dividerExpand)
        val groupExpanded: View = view.findViewById(R.id.groupExpanded)

        val statusHardware: StatusIndicatorView = view.findViewById(R.id.statusHardware)
        val statusRuntime: StatusIndicatorView = view.findViewById(R.id.statusSensorRuntime)
        val statusRos: StatusIndicatorView = view.findViewById(R.id.statusSensorRos)
        val tvControlBadge: TextView = view.findViewById(R.id.tvControlBadge)
        val tvTopic: TextView = view.findViewById(R.id.tvTopic)

        val btnStart: MaterialButton = view.findViewById(R.id.btnSensorStart)
        val btnStop: MaterialButton = view.findViewById(R.id.btnSensorStop)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sensor, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sensor = getItem(position)

        holder.ivIcon.setImageResource(sensor.iconRes)
        holder.tvName.text = sensor.name

        // 1. Determine "glance" status (combined)
        // If hardware missing -> NOT_AVAILABLE
        // Else if runtime error -> ERROR
        // Else if runtime running -> RUNNING (green)
        // Else -> STOPPED (gray)
        val glanceText: String
        val glanceColor: Int
        when {
            !sensor.hardware -> {
                glanceText = "N/A"
                glanceColor = R.color.asr_status_not_available
            }
            sensor.runtime == SensorRuntimeState.ERROR -> {
                glanceText = "ERROR"
                glanceColor = R.color.asr_status_error
            }
            sensor.runtime == SensorRuntimeState.RUNNING -> {
                glanceText = "ACTIVE"
                glanceColor = R.color.asr_status_running
            }
            else -> {
                glanceText = "STOPPED"
                glanceColor = R.color.asr_status_stopped
            }
        }
        holder.statusCombined.setStatus(glanceText, glanceColor)

        // 2. Data rate
        if (!sensor.rate.isNullOrEmpty() && sensor.runtime == SensorRuntimeState.RUNNING) {
            holder.tvRate.text = sensor.rate
            holder.tvRate.visibility = View.VISIBLE
        } else {
            holder.tvRate.visibility = View.GONE
        }

        // 3. Expansion
        holder.dividerExpand.visibility = if (sensor.expanded) View.VISIBLE else View.GONE
        holder.groupExpanded.visibility = if (sensor.expanded) View.VISIBLE else View.GONE
        holder.ivChevron.rotation = if (sensor.expanded) 180f else 0f

        // 4. Details (expanded only)
        if (sensor.expanded) {
            holder.statusHardware.setLabel("Hardware")
            holder.statusHardware.setStatus(
                if (sensor.hardware) "PRESENT" else "MISSING",
                if (sensor.hardware) R.color.asr_status_running else R.color.asr_status_not_available
            )

            holder.statusRuntime.setLabel("Runtime")
            holder.statusRuntime.setStatus(sensor.runtime.label, sensor.runtime.color)

            holder.statusRos.setLabel("ROS 2")
            holder.statusRos.setStatus(sensor.ros.label, sensor.ros.color)

            holder.tvControlBadge.text = sensor.control.label
            holder.tvControlBadge.setTextColor(ContextCompat.getColor(holder.itemView.context, sensor.control.color))

            if (!sensor.topic.isNullOrEmpty()) {
                holder.tvTopic.text = sensor.topic
                holder.tvTopic.visibility = View.VISIBLE
            } else {
                holder.tvTopic.visibility = View.GONE
            }

            // START/STOP buttons
            // Disable if REMOTE control is active (only ROS can control)
            val canControlLocally = sensor.control != ControlSource.REMOTE && sensor.hardware
            holder.btnStart.isEnabled = canControlLocally && sensor.runtime != SensorRuntimeState.RUNNING
            holder.btnStop.isEnabled = canControlLocally && sensor.runtime == SensorRuntimeState.RUNNING
        }

        // Click listeners
        holder.rowCollapsed.setOnClickListener { onToggle(sensor) }
        holder.btnStart.setOnClickListener { onStart(sensor) }
        holder.btnStop.setOnClickListener { onStop(sensor) }
    }

    object DiffCallback : DiffUtil.ItemCallback<SensorUiModel>() {
        override fun areItemsTheSame(oldItem: SensorUiModel, newItem: SensorUiModel) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: SensorUiModel, newItem: SensorUiModel) = oldItem == newItem
    }
}
