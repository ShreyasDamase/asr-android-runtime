package com.asr.runtime.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.asr.runtime.R
import com.google.android.material.button.MaterialButton

private const val PAYLOAD_EXPAND_CHANGED = "expand_changed"

class SensorAdapter(
    private val onToggleExpand: (SensorUiModel) -> Unit,
    private val onSensorStart: (SensorUiModel) -> Unit,
    private val onSensorStop: (SensorUiModel) -> Unit
) : ListAdapter<SensorUiModel, SensorAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sensor, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bindFull(getItem(position))
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_EXPAND_CHANGED)) {
            holder.bindExpandOnly(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val rowCollapsed: LinearLayout = itemView.findViewById(R.id.rowCollapsed)
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvRate: TextView = itemView.findViewById(R.id.tvRate)
        private val statusCombined: StatusIndicatorView = itemView.findViewById(R.id.statusCombined)
        private val ivChevron: ImageView = itemView.findViewById(R.id.ivChevron)
        private val dividerExpand: View = itemView.findViewById(R.id.dividerExpand)
        private val groupExpanded: LinearLayout = itemView.findViewById(R.id.groupExpanded)
        private val statusHardware: StatusIndicatorView = itemView.findViewById(R.id.statusHardware)
        private val statusSensorRuntime: StatusIndicatorView = itemView.findViewById(R.id.statusSensorRuntime)
        private val statusSensorRos: StatusIndicatorView = itemView.findViewById(R.id.statusSensorRos)
        private val tvControlBadge: TextView = itemView.findViewById(R.id.tvControlBadge)
        private val tvTopic: TextView = itemView.findViewById(R.id.tvTopic)
        private val btnSensorStart: MaterialButton = itemView.findViewById(R.id.btnSensorStart)
        private val btnSensorStop: MaterialButton = itemView.findViewById(R.id.btnSensorStop)

        fun bindFull(model: SensorUiModel) {
            ivIcon.setImageResource(model.iconRes)
            tvName.text = model.displayName

            if (model.rateHz != null && model.ros == RosPublishState.PUBLISHING) {
                tvRate.visibility = View.VISIBLE
                tvRate.text = String.format("%.1f Hz", model.rateHz)
            } else {
                tvRate.visibility = View.GONE
            }

            val (label, colorRes) = model.combinedSeverity()
            statusCombined.setStatus(label, colorRes)

            statusHardware.setStatus(
                if (model.hardware == HardwareAvailability.AVAILABLE) "AVAILABLE" else "NOT AVAILABLE",
                if (model.hardware == HardwareAvailability.AVAILABLE) R.color.asr_status_running else R.color.asr_status_not_available
            )
            statusSensorRuntime.setStatus(model.runtime.name, colorForRuntime(model.runtime))
            statusSensorRos.setStatus(
                if (model.ros == RosPublishState.PUBLISHING) "PUBLISHING" else "NOT PUBLISHING",
                if (model.ros == RosPublishState.PUBLISHING) R.color.asr_ros_publishing else R.color.asr_ros_not_publishing
            )

            tvControlBadge.text = model.control.name
            tvControlBadge.setTextColor(itemView.context.getColor(colorForControl(model.control)))

            if (model.topic != null) {
                tvTopic.visibility = View.VISIBLE
                tvTopic.text = model.topic
            } else {
                tvTopic.visibility = View.GONE
            }

            // Local start/stop disabled under REMOTE control — never hidden.
            val localControlAllowed = model.control != ControlSource.REMOTE &&
                model.hardware == HardwareAvailability.AVAILABLE
            btnSensorStart.isEnabled = localControlAllowed && model.runtime != SensorRuntimeState.RUNNING
            btnSensorStop.isEnabled = localControlAllowed && model.runtime == SensorRuntimeState.RUNNING

            rowCollapsed.setOnClickListener { onToggleExpand(model) }
            btnSensorStart.setOnClickListener { onSensorStart(model) }
            btnSensorStop.setOnClickListener { onSensorStop(model) }

            bindExpandOnly(model)
        }

        fun bindExpandOnly(model: SensorUiModel) {
            groupExpanded.visibility = if (model.expanded) View.VISIBLE else View.GONE
            dividerExpand.visibility = if (model.expanded) View.VISIBLE else View.GONE
            ivChevron.rotation = if (model.expanded) 180f else 0f
        }

        private fun colorForRuntime(state: SensorRuntimeState): Int = when (state) {
            SensorRuntimeState.RUNNING -> R.color.asr_status_running
            SensorRuntimeState.STOPPED -> R.color.asr_status_stopped
            SensorRuntimeState.BLOCKED -> R.color.asr_status_blocked
            SensorRuntimeState.ERROR -> R.color.asr_status_error
        }

        private fun colorForControl(source: ControlSource): Int = when (source) {
            ControlSource.LOCAL -> R.color.asr_control_local
            ControlSource.REMOTE -> R.color.asr_control_remote
            ControlSource.AUTO -> R.color.asr_control_auto
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SensorUiModel>() {
            override fun areItemsTheSame(old: SensorUiModel, new: SensorUiModel) = old.id == new.id
            override fun areContentsTheSame(old: SensorUiModel, new: SensorUiModel) = old == new

            override fun getChangePayload(old: SensorUiModel, new: SensorUiModel): Any? {
                // If ONLY `expanded` differs, tell the adapter to skip the full rebind.
                val onlyExpandDiffers = old.copy(expanded = new.expanded) == new
                return if (onlyExpandDiffers) PAYLOAD_EXPAND_CHANGED else null
            }
        }
    }
}
